/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.data.lyrics.LrcLibApiService
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every online source the app knows how to ask, keyed by the entry the picker
 * shows.
 *
 * A provider that hangs, errors out or is simply not configured is a miss, not
 * a failure: [fetch] swallows everything but cancellation so one bad host
 * cannot take the whole lookup down with it.
 */
internal class LyricsProviderRegistry(
  lrcLibApiService: LrcLibApiService,
) {
  private val clients: Map<LyricsProvider, LyricsProviderClient> =
    mapOf(
      LyricsProvider.BINI_LYRICS to BiniLyricsProvider,
      LyricsProvider.BETTER_LYRICS to
        BetterLyricsProvider(LyricsProvider.BETTER_LYRICS, BetterLyricsProvider.APPLE),
      LyricsProvider.BETTER_LYRICS_PORTATO to
        BetterLyricsProvider(LyricsProvider.BETTER_LYRICS_PORTATO, BetterLyricsProvider.PORTATO),
      LyricsProvider.PAXSENIX to PaxSenixProvider(LyricsProvider.PAXSENIX, PaxSenixRoute.APPLE),
      LyricsProvider.PAXSENIX_SPOTIFY to PaxSenixProvider(LyricsProvider.PAXSENIX_SPOTIFY, PaxSenixRoute.SPOTIFY),
      LyricsProvider.PAXSENIX_MUSIXMATCH to
        PaxSenixProvider(LyricsProvider.PAXSENIX_MUSIXMATCH, PaxSenixRoute.MUSIXMATCH),
      LyricsProvider.LYRICS_PLUS to LyricsPlusProvider,
      LyricsProvider.SIMP_MUSIC to SimpMusicProvider,
      LyricsProvider.UNISON to UnisonProvider,
      LyricsProvider.YOUTUBE_TRANSCRIPT to YouTubeTranscriptProvider,
      LyricsProvider.YOUTUBE_MUSIC to YouTubeMusicLyricsProvider,
      LyricsProvider.MEGALOBIZ to MegalobizProvider,
      LyricsProvider.KUGOU to KuGouProvider,
      LyricsProvider.LRCLIB to LrcLibProvider(lrcLibApiService),
      LyricsProvider.MUSIXMATCH to MusixmatchProvider,
      LyricsProvider.GENIUS to GeniusProvider,
    )

  /** Lyrics from one provider, or null when it does not have this track. */
  suspend fun fetch(
    provider: LyricsProvider,
    query: LyricsFetchQuery,
  ): Lyrics? {
    val client = clients[provider] ?: return null
    return withTimeoutOrNull(budgetFor(provider)) {
      runCatching { client.fetch(query) }
        .getOrElse { error -> if (error is CancellationException) throw error else null }
    }
  }

  /**
   * How long one provider may take before the lookup stops waiting on it.
   *
   * Most chains are a handful of short calls; the routes that have to load a
   * page and then a script before they can ask for anything need longer than
   * the rest, which is why this is per-provider rather than one number for the
   * whole race.
   */
  private fun budgetFor(provider: LyricsProvider): Long =
    when (provider) {
      LyricsProvider.PAXSENIX,
      LyricsProvider.PAXSENIX_SPOTIFY,
      LyricsProvider.PAXSENIX_MUSIXMATCH,
      LyricsProvider.MUSIXMATCH,
      -> PATIENT_BUDGET_MS
      else -> BUDGET_MS
    }

  private companion object {
    const val BUDGET_MS = 10_000L
    const val PATIENT_BUDGET_MS = 20_000L
  }
}
