/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's SimpMusic provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Lyrics from SimpMusic's community database, keyed on the YouTube video id.
 *
 * That key is what makes it worth having: every other provider matches on a
 * title and an artist and can hand back a different edit of the same song,
 * which drifts out of sync a verse in. This one is looking up the exact track
 * that is playing.
 *
 * Two caveats, both seen in the wild: the host geoblocks some regions
 * outright, answering 403 with an "Access denied from your region" body rather
 * than a network error — so a miss here can be permanent for a given user and
 * the race has to carry on past it — and the rich sync is served HTML-escaped.
 */
internal object SimpMusicProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.SIMP_MUSIC

  private const val BASE = "https://api-lyrics.simpmusic.org/v1/"

  /** Duration slack for when the database holds several cuts of one video. */
  private const val DURATION_TOLERANCE_SECONDS = 10

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val videoId = query.videoId?.takeIf { YOUTUBE_ID.matches(it) } ?: return null
    val body = lyricsGet(BASE + videoId) ?: return null
    val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull() ?: return null
    if (!response.success) return null

    val seconds = query.durationMs / 1000
    val track =
      response.data
        .orEmpty()
        .filter { seconds <= 0 || abs((it.duration ?: 0) - seconds) <= DURATION_TOLERANCE_SECONDS }
        .minByOrNull { abs((it.duration ?: 0) - seconds) }
        ?: return null

    // Word timing first; a line-synced answer from here is no better than
    // LRCLIB's, but it is still better than nothing.
    val rich = track.richSyncLyrics?.takeIf { it.isNotBlank() }?.let { parseLrcText(decodeEntities(it)) }
    if (rich != null && !rich.synced.isNullOrEmpty()) return rich
    return track.syncedLyrics?.takeIf { it.isNotBlank() }?.let { parseLrcText(decodeEntities(it)) }
  }

  internal val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")

  @Serializable
  private data class Response(
    val success: Boolean = false,
    val data: List<Track>? = null,
  )

  @Serializable
  private data class Track(
    val duration: Int? = null,
    val richSyncLyrics: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
  )
}
