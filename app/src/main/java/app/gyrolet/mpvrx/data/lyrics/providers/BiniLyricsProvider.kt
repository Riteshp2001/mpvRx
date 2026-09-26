/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Apple Music TTML from a third host, and the only provider here that will
 * answer to a recording rather than to a name.
 *
 * The catalogue is the same one BetterLyrics serves; what this adds is a
 * different matcher over it, which finds tracks BetterLyrics misses —
 * particularly outside the English-language releases — and, more usefully, an
 * index by ISRC. A search reports the ISRC of whatever it matched, so a later
 * lookup can name the recording instead of describing it.
 *
 * Two requests either way: the search returns a URL rather than the document,
 * so the TTML itself is a second fetch from the storage host.
 */
internal object BiniLyricsProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.BINI_LYRICS

  private const val BASE = "https://lyrics-api.binimum.org/"

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val hit = identify(query) ?: return null
    return fetchDocument(hit)
  }

  /** Which recording this is, without fetching its words. */
  private suspend fun identify(query: LyricsFetchQuery): Hit? {
    val isrc = query.isrc?.takeIf { it.isNotBlank() }
    if (isrc != null) {
      return load(buildUrl(BASE, "isrc" to isrc))
    }
    for (ref in query.candidates()) {
      val seconds = (query.durationMs / 1000).takeIf { it > 0 }?.toString()
      val url =
        buildUrl(
          BASE,
          "track" to ref.title,
          "artist" to ref.artist,
          "album" to query.album,
          "duration" to seconds,
        ) ?: continue
      load(url)?.let { return it }
    }
    return null
  }

  private suspend fun load(url: String?): Hit? {
    val body = url?.let { lyricsGet(it) } ?: return null
    return runCatching { lyricsJson.decodeFromString<Response>(body) }
      .getOrNull()
      ?.results
      ?.firstOrNull()
  }

  private suspend fun fetchDocument(hit: Hit): Lyrics? {
    val document = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return null
    val ttml = lyricsGet(document) ?: return null
    return TtmlLyrics.parse(ttml)?.let(::lyricsFromSynced)
  }

  @Serializable
  private data class Response(
    val total: Int? = null,
    val source: String? = null,
    val results: List<Hit>? = null,
  )

  @Serializable
  private data class Hit(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    val duration: Int? = null,
    val isrc: String? = null,
    @SerialName("timing_type") val timingType: String? = null,
    val lyricsUrl: String? = null,
  )
}
