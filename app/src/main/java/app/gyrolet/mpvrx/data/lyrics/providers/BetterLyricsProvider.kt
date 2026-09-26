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
 * One key-less call keyed on title, artist and duration, answering with Apple
 * Music's own TTML. That combination is why it sits near the front of the
 * order: no track-id lookup, no token to scrape, no login, and the timing is
 * per-syllable.
 *
 * The endpoint used here is the extension's original host; the project's newer
 * API puts the same route behind a challenge a native client cannot answer.
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
        ) ?: return null
      val body = lyricsGet(url) ?: continue
      parseProviderText(body)?.let { return it }
    }
    return null
  }

  companion object {
    const val APPLE = "https://lyrics-api.boidu.dev/getLyrics"
    const val PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"
  }
}
