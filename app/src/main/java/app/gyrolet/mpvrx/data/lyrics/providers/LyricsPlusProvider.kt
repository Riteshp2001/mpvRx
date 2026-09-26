/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's LyricsPlus provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

/**
 * Syllable-timed lyrics from LyricsPlus, the open backend behind the YouLy+
 * extension. It aggregates Apple Music, QQ Music and Musixmatch, and its v2
 * response is the finest-grained of the providers here — Apple's own syllable
 * splits, not just word boundaries.
 *
 * The catch is hosting: it runs on volunteer mirrors, and at any moment most of
 * them are rate-limited, out of credit or simply gone. So all of them are asked
 * at once and the first real answer wins, and the host that answered is
 * remembered so the next track goes straight to one that was up a minute ago.
 */
internal object LyricsPlusProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.LYRICS_PLUS

  private val MIRRORS =
    listOf(
      "https://lyricsplus.prjktla.my.id",
      "https://lyricsplus.atomix.one",
      "https://lyricsplus.binimum.org",
      "https://lyricsplus.prjktla.workers.dev",
      "https://lyricsplus-seven.vercel.app",
      "https://lyrics-plus-backend.vercel.app",
    )

  private val lastGood = AtomicReference<String?>(null)

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val preferred = lastGood.get()
    val hosts = preferred?.let { listOf(it) + MIRRORS.filterNot { mirror -> mirror == it } } ?: MIRRORS
    return withContext(Dispatchers.IO) {
      coroutineScope {
        val pending =
          hosts
            .map { host ->
              host to async { fetchFrom(host, query) }
            }.toMutableList()

        try {
          // The first mirror to answer with something usable, not the first to
          // answer at all — one that 404s this track should not beat one that
          // has it.
          while (pending.isNotEmpty()) {
            val (host, lyrics) =
              select {
                pending.forEach { (host, job) -> job.onAwait { host to it } }
              }
            pending.removeAll { it.first == host }
            if (lyrics != null) {
              lastGood.set(host)
              return@coroutineScope lyrics
            }
          }
          null
        } finally {
          pending.forEach { it.second.cancel() }
        }
      }
    }
  }

  private suspend fun fetchFrom(
    host: String,
    query: LyricsFetchQuery,
  ): Lyrics? {
    for (ref in query.candidates()) {
      val url =
        buildUrl(
          "$host/v2/lyrics/get",
          "title" to ref.title,
          "artist" to ref.artist,
          "duration" to (query.durationMs / 1000).takeIf { it > 0 }?.toString(),
          "album" to query.album,
          // Sent alongside the name rather than instead of it: this backend
          // aggregates several catalogues, and the ones with no ISRC index still
          // need something to match on.
          "isrc" to query.isrc,
        ) ?: return null
      val body = lyricsGet(url) ?: continue
      val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull() ?: continue
      parse(response)?.let { return it }
    }
    return null
  }

  private fun parse(response: Response): Lyrics? {
    val sung =
      response.lyrics.orEmpty().mapNotNull { line ->
        val start = line.time?.inMs() ?: return@mapNotNull null
        val words = mergeSyllables(line.syllabus.orEmpty())
        val built =
          when {
            words.isNotEmpty() ->
              SyncedLine(
                time = minOf(start, words.first().time),
                line = words.joinToString(" ") { it.word },
                words = words,
              )
            // Some sources are only line-synced; still worth showing.
            !line.text.isNullOrBlank() -> SyncedLine(time = start, line = line.text.trim())
            else -> null
          }
        built?.let { it to (start + (line.duration ?: 0L).inMs()) }
      }

    val lines =
      sung.sortedBy { it.first.time }.map { (line, end) ->
        TimedLine(line, maxOf(end, line.time))
      }
    return lyricsFromSynced(lines.withInstrumentalGaps())
  }

  /**
   * Glues syllables back into words. The API's own spacing is the word
   * boundary — it emits "e" then "nough " and the trailing space is the only
   * thing saying those are one word — so splitting on the syllable instead
   * would render "e nough".
   */
  private fun Long.inMs(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

  private fun mergeSyllables(syllables: List<Syllable>): List<SyncedWord> {
    val words = mutableListOf<SyncedWord>()
    val current = StringBuilder()
    var start = 0

    syllables.forEach { syllable ->
      val text = syllable.text ?: return@forEach
      if (text.isBlank()) return@forEach
      val time = syllable.time?.inMs() ?: return@forEach
      if (current.isEmpty()) start = time
      current.append(text.trim())
      if (text.last().isWhitespace()) {
        words += SyncedWord(time = start, word = current.toString(), startsNewWord = true)
        current.setLength(0)
      }
    }
    if (current.isNotEmpty()) words += SyncedWord(time = start, word = current.toString(), startsNewWord = true)
    return words
  }

  @Serializable
  private data class Response(
    val type: String? = null,
    val lyrics: List<Line>? = null,
    val metadata: Metadata? = null,
  )

  @Serializable
  private data class Metadata(
    val agents: Map<String, Agent>? = null,
  )

  @Serializable
  private data class Agent(
    val type: String? = null,
    val alias: String? = null,
  )

  @Serializable
  private data class Line(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    @SerialName("syllabus") val syllabus: List<Syllable>? = null,
  )

  @Serializable
  private data class Syllable(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
  )
}
