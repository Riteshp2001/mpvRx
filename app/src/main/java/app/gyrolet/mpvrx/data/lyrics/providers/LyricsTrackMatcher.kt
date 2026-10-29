/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Reject fuzzy catalogue matches belonging to a different song, edit or artist. */
internal object LyricsTrackMatcher {
  private const val MAX_DURATION_DIFFERENCE_SECONDS = 12.0
  private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
  private val TRAILING_FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.+$""", RegexOption.IGNORE_CASE)
  private val DASH = Regex("""\s+[-–—]\s+""")
  private val MARKS = Regex("""\p{Mn}+""")
  private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
  private val SPACES = Regex("""\s+""")
  private val ARTIST_SPLIT = Regex("""\s*(?:,|&|;|/|\+|\band\b|\bwith\b|\bfeat\.?|\bft\.?|\bx\b)\s*""", RegexOption.IGNORE_CASE)
  private val VERSION_WORDS = setOf(
    "live", "remix", "remixed", "mix", "acoustic", "unplugged",
    "instrumental", "karaoke", "demo", "edit", "cover", "sped",
    "slowed", "nightcore", "lofi", "extended", "remaster", "remastered",
  )

  fun matches(
    ref: TrackRef,
    title: String?,
    artist: String?,
    durationSeconds: Int = 0,
    resultDurationSeconds: Double = 0.0,
  ): Boolean {
    if (ref.title.isBlank() || title.isNullOrBlank()) return false
    if (core(ref.title) != core(title)) return false
    if (versions(ref.title) != versions(title)) return false

    if (ref.artist.isNotBlank()) {
      if (artist.isNullOrBlank()) return false
      val wanted = artists(ref.artist)
      val found = artists(artist)
      if (wanted.isEmpty() || found.none { it in wanted }) return false
    }

    return durationSeconds <= 0 || resultDurationSeconds <= 0 ||
      abs(resultDurationSeconds - durationSeconds) <= MAX_DURATION_DIFFERENCE_SECONDS
  }

  private fun core(title: String): String {
    val plain = BRACKETS.replace(title, " ").replace(TRAILING_FEAT, "").trim()
    val parts = plain.split(DASH, limit = 2)
    val withoutVersionSuffix =
      if (parts.size > 1 && versions(parts[1]).isNotEmpty()) parts[0] else plain
    return fold(withoutVersionSuffix)
  }

  private fun versions(title: String): Set<String> =
    fold(title).split(' ').filter { it in VERSION_WORDS }.toSet()

  private fun artists(artist: String): Set<String> =
    artist.split(ARTIST_SPLIT).map(::fold).filter { it.isNotEmpty() }.toSet()

  private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
      .replace(MARKS, "")
      .lowercase(Locale.ROOT)
      .replace(NON_ALPHANUMERIC, " ")
      .replace(SPACES, " ")
      .trim()
}
