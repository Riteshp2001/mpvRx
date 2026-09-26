/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.data.lyrics.LrcLibApiService
import app.gyrolet.mpvrx.data.lyrics.LrcLibResponse
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlin.math.abs

/**
 * LRCLIB — a free, key-less, community database.
 *
 * An exact `get` keyed on artist, title and duration, then fuzzy `searches`
 * when that misses — once on the pair, once on "artist title" as one phrase —
 * because titles arrive carrying "(From ...)" and "| Official Video" noise that
 * the exact endpoint will not match. Results are line-synced LRC, so
 * highlighting is per line. At most [MAX_ATTEMPTS] calls per track.
 */
internal class LrcLibProvider(
  private val api: LrcLibApiService,
) : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.LRCLIB

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val duration = query.durationMs / 1000
    var plainFallback: Lyrics? = null
    var attempts = 0

    for (ref in query.candidates()) {
      if (attempts >= MAX_ATTEMPTS) break

      if (duration > 0 && ref.artist.isNotBlank() && attempts < MAX_ATTEMPTS) {
        attempts++
        val exact =
          api.getLyrics(trackName = ref.title, artistName = ref.artist, duration = duration)
            ?: api.getLyrics(trackName = ref.title, artistName = ref.artist)
        synced(exact?.syncedLyrics)?.let { return it }
        if (plainFallback == null) plainFallback = plain(exact?.plainLyrics)
      }

      if (attempts < MAX_ATTEMPTS) {
        attempts++
        val hits =
          if (ref.artist.isNotBlank()) {
            api.searchLyrics(trackName = ref.title, artistName = ref.artist)
          } else {
            api.searchLyrics(query = ref.title)
          }
        val best = bestHit(hits, duration)
        synced(best)?.let { return it }
        if (plainFallback == null) plainFallback = plain(best)
      }

      if (ref.artist.isNotBlank() && attempts < MAX_ATTEMPTS) {
        attempts++
        val hits = api.searchLyrics(query = "${ref.artist} ${ref.title}")
        val best = bestHit(hits, duration)
        synced(best)?.let { return it }
        if (plainFallback == null) plainFallback = plain(best)
      }
    }

    return plainFallback
  }

  /** Synced lyrics only — a plain answer here is a miss for this provider. */
  private fun synced(raw: String?): Lyrics? =
    parseLrcText(raw.orEmpty())
      ?.takeIf { !it.synced.isNullOrEmpty() }

  private fun plain(raw: String?): Lyrics? =
    parseLrcText(raw.orEmpty())
      ?.takeIf { it.synced.isNullOrEmpty() }

  /**
   * Prefers whichever hit is closest in length to what is actually playing —
   * the same song in a different edit would otherwise drift a verse in. Synced
   * hits are considered before plain ones for the same reason: a timed answer
   * that is ten seconds off beats an untimed one that is perfect.
   */
  private fun bestHit(
    hits: List<LrcLibResponse>,
    seconds: Int,
  ): String? {
    val syncedHits = hits.filter { !it.syncedLyrics.isNullOrBlank() }
    val ordered = syncedHits.ifEmpty { hits.filter { !it.plainLyrics.isNullOrBlank() } }
    if (ordered.isEmpty()) return null
    val best =
      if (seconds > 0) {
        ordered.minByOrNull { hit ->
          if (hit.duration > 0) abs(hit.duration - seconds) else 20.0
        } ?: ordered.first()
      } else {
        ordered.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: ordered.first()
      }
    return best.syncedLyrics?.takeIf { it.isNotBlank() } ?: best.plainLyrics?.takeIf { it.isNotBlank() }
  }

  private companion object {
    const val MAX_ATTEMPTS = 8
  }
}
