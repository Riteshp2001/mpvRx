/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.lyrics

/**
 * Online lyrics databases the repository can ask, in default priority order.
 *
 * Declaration order is the priority inside one quality tier. Every provider is
 * contacted together; actual word-timed lyrics beat line-timed lyrics, which
 * beat plain text. When two providers return the same timing quality, the one
 * declared first wins. The picker uses this order too.
 *
 * [wordSynced] describes provider capability for UI and diagnostics. Automatic
 * selection inspects the returned lyrics because a capable provider can still
 * return a line-synced fallback for one particular track.
 */
enum class LyricsProvider(
  val label: String,
  val detail: String,
  val wordSynced: Boolean,
) {
  LRC_RED(
    label = "lrc.red",
    detail = "Apple Music TTML matched by recording ID or title and artist",
    wordSynced = true,
  ),
  BINI_LYRICS(
    label = "BiniLyrics",
    detail = "Apple Music TTML matched by metadata or ISRC",
    wordSynced = true,
  ),
  BETTER_LYRICS(
    label = "BetterLyrics",
    detail = "Cached Apple Music timings; uncached songs may require authorization",
    wordSynced = true,
  ),
  BETTER_LYRICS_PORTATO(
    label = "BetterLyrics Portato",
    detail = "QQ Music karaoke timings through BetterLyrics",
    wordSynced = true,
  ),
  PAXSENIX(
    label = "PaxSenix",
    detail = "Apple Music timings through a keyless proxy",
    wordSynced = true,
  ),
  PAXSENIX_SPOTIFY(
    label = "PaxSenix: Spotify",
    detail = "Spotify lyrics through PaxSenix; your PaxSenix API key is required",
    wordSynced = false,
  ),
  PAXSENIX_MUSIXMATCH(
    label = "PaxSenix: Musixmatch",
    detail = "Musixmatch lyrics through PaxSenix; your PaxSenix API key is required",
    wordSynced = true,
  ),
  LYRICS_PLUS(
    label = "LyricsPlus",
    detail = "Word, line or plain lyrics from community mirrors; availability varies",
    wordSynced = true,
  ),
  SIMP_MUSIC(
    label = "SimpMusic",
    detail = "Community lyrics linked to the playing YouTube video; availability varies by region",
    wordSynced = true,
  ),
  UNISON(
    label = "Unison",
    detail = "Community-submitted TTML, LRC and plain lyrics",
    wordSynced = true,
  ),
  YOUTUBE_TRANSCRIPT(
    label = "YouTube captions",
    detail = "Timed captions of the playing video",
    wordSynced = false,
  ),
  YOUTUBE_MUSIC(
    label = "YouTube Music",
    detail = "Plain lyrics from the video's Lyrics tab",
    wordSynced = false,
  ),
  MEGALOBIZ(
    label = "Megalobiz",
    detail = "Community-made, whole-line LRC",
    wordSynced = false,
  ),
  KUGOU(
    label = "KuGou",
    detail = "Whole lines, strong outside the English catalogue",
    wordSynced = false,
  ),
  LRCLIB(
    label = "LRCLIB",
    detail = "Public lyrics database with synced and plain-text results",
    wordSynced = false,
  ),
  MUSIXMATCH(
    label = "Musixmatch",
    detail = "Word timing with a line-synced fallback; web service availability varies",
    wordSynced = true,
  ),
  GENIUS(
    label = "Genius",
    detail = "Plain-text lyrics from Genius pages when timed sources are unavailable",
    wordSynced = false,
  ),
  ;

  companion object {
    /** Play builds may only call LRCLIB's public API; the others are unofficial or scraped endpoints. */
    val available: List<LyricsProvider> =
      if (app.gyrolet.mpvrx.BuildConfig.IS_PLAY_STORE_BUILD) listOf(LRCLIB) else LyricsProvider.entries

    /**
     * Contacted only when nothing above [GENIUS] has answered: it is a plain
     * web scrape, so it stays out of the way of the sources that can be asked
     * politely.
     */
    val LAZY_PROVIDERS: Set<LyricsProvider> = setOf(GENIUS)
  }
}
