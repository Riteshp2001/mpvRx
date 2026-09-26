/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's Unison provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.serialization.Serializable

/**
 * A community-submitted database and the only provider here whose contents are
 * contributed rather than licensed — which is its whole character. It carries
 * what people have uploaded, so it occasionally has a track none of the others
 * do, and its coverage is thin everywhere else.
 *
 * Three shapes come back and the entry says which: Apple-style TTML, ordinary
 * line-stamped LRC, or plain text with no timing at all.
 */
internal object UnisonProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.UNISON

  private const val BASE = "https://unison.boidu.dev/lyrics"

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    for (ref in query.candidates()) {
      val url =
        buildUrl(
          BASE,
          "song" to ref.title,
          "artist" to ref.artist,
          "album" to query.album,
          "duration" to (query.durationMs / 1000).takeIf { it > 0 }?.toString(),
        ) ?: return null
      val body = lyricsGet(url) ?: continue
      val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull() ?: continue
      if (response.success != true) continue
      parse(response.data ?: continue)?.let { return it }
    }
    return null
  }

  /** The three shapes, told apart by what the entry says it is. */
  private fun parse(entry: Entry): Lyrics? {
    val text = entry.lyrics?.takeIf { it.isNotBlank() } ?: return null
    return when {
      entry.format.equals("ttml", ignoreCase = true) ->
        TtmlLyrics.parse(text)?.let(::lyricsFromSynced)
      entry.syncType.equals(PLAIN, ignoreCase = true) -> lyricsFromPlain(text)
      // Word stamps first: the format field says only "lrc", and an enhanced
      // file still is one. A document without word stamps parses as ordinary
      // LRC, which is the fall-through rather than a failure.
      else -> parseProviderText(text) ?: parseLrcText(text)
    }
  }

  private const val PLAIN = "plain"

  @Serializable
  private data class Response(
    val success: Boolean? = null,
    val data: Entry? = null,
  )

  @Serializable
  private data class Entry(
    val song: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val lyrics: String? = null,
    /** `ttml` or `lrc`. */
    val format: String? = null,
    /** `wordsync`, `linesync` or `plain`. */
    val syncType: String? = null,
    val confidence: String? = null,
    val voteCount: Int? = null,
  )
}
