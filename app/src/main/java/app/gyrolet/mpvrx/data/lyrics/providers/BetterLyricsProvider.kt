/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider

/**
 * Word-timed lyrics from BetterLyrics — the backend behind the YouTube Music
 * browser extension of the same name.
 *
 * One call keyed on title, artist, album and duration, answering with Apple
 * Music TTML with per-syllable timing when it is available.
 *
 * The current public API serves cached TTML without a key. Uncached TTML may
 * require authorization; the QQ route does not. Failures leave other providers usable.
 */
internal class BetterLyricsProvider(
  override val id: LyricsProvider,
  private val endpoint: String,
) : LyricsProviderClient {
  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val seconds = (query.durationMs / 1000).takeIf { it > 0 }?.toString()
    for (ref in query.candidates()) {
      val url =
        buildUrl(
          endpoint,
          "s" to ref.title,
          "a" to ref.artist,
          "d" to seconds,
          "al" to query.album,
          "videoId" to query.videoId,
        ) ?: return null
      val body = lyricsGet(url, patient = true) ?: continue
      parseProviderText(body)?.let { return it }
    }
    return null
  }

  companion object {
    const val APPLE = "https://api.betterlyrics.org/getLyrics"
    const val PORTATO = "https://api.betterlyrics.org/qq/getLyrics"
  }
}
