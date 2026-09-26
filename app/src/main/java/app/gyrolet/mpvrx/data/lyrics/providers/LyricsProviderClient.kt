/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider

/** One title/artist pair a lookup was given, as the playing file described it. */
data class TrackRef(
  val title: String,
  val artist: String,
) {
  fun isUsable(): Boolean = title.isNotBlank()

  fun key(): String = "${title.trim()}|${artist.trim()}".lowercase()
}

/**
 * Everything a provider is told about the track being played.
 *
 * [refs] is ordered and already cleaned — the repository does the guessing about
 * which segment of a YouTube title is the artist, which part of an
 * "Artist - Title" line is which, and hands every provider the same list. A
 * provider walks it until something matches rather than re-deriving names for
 * itself, so a miss means "not in this catalogue" and not "asked the wrong way".
 */
data class LyricsFetchQuery(
  val refs: List<TrackRef>,
  val durationMs: Int = 0,
  val album: String? = null,
  val videoId: String? = null,
  val isrc: String? = null,
) {
  /** The lookup as the metadata states it, before any of the guesses. */
  val primary: TrackRef
    get() = refs.firstOrNull() ?: TrackRef("", "")

  /** Candidate names to try, bounded so one provider cannot fan out into a storm. */
  fun candidates(limit: Int = MAX_CANDIDATES): List<TrackRef> =
    refs
      .asSequence()
      .filter { it.isUsable() }
      .distinctBy { it.key() }
      .take(limit)
      .toList()

  companion object {
    const val MAX_CANDIDATES: Int = 4
  }
}

/**
 * One online lyrics database, reachable the same way as every other.
 *
 * Implementations are expected to fail quietly: a provider that is geoblocked,
 * rate-limited or simply missing this track returns null and lets the race move
 * on. Throwing is reserved for genuine programmer errors, never for a miss.
 */
interface LyricsProviderClient {
  val id: LyricsProvider

  suspend fun fetch(query: LyricsFetchQuery): Lyrics?
}
