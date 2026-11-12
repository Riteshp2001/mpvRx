/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.lyrics

import kotlinx.serialization.Serializable

enum class LyricsSourceType {
  EMBEDDED,
  ONLINE,
  LOCAL,
}

@Serializable
data class Lyrics(
  val plain: List<String>? = null,
  val synced: List<SyncedLine>? = null,
  val areFromRemote: Boolean = false,
  val sourceType: LyricsSourceType = LyricsSourceType.ONLINE,
) {
  fun isValid(): Boolean = synced.orEmpty().any { it.line.isNotBlank() } || plain.orEmpty().any { it.isNotBlank() }

  /** How closely the provider's timestamps follow the singing. */
  fun timing(): LyricsTiming {
    val lines = synced.orEmpty().filter { it.line.isNotBlank() }
    if (lines.isEmpty()) return LyricsTiming.PLAIN
    // A whole song stamped at a few moments (often all at 00:00) cannot follow anything.
    // Exactly half still passes: some LRC files give each translation its original's stamp.
    if (lines.mapTo(HashSet()) { it.time }.size * 2 < lines.size) return LyricsTiming.PLAIN
    val moving = lines.count { it.hasMovingWords() || it.background?.hasMovingWords() == true }
    return when {
      moving * 100 >= lines.size * FULL_WORD_TIMING_PERCENT -> LyricsTiming.WORD
      moving * 100 >= lines.size * PARTIAL_WORD_TIMING_PERCENT -> LyricsTiming.PARTIAL_WORD
      else -> LyricsTiming.LINE
    }
  }
}

private const val FULL_WORD_TIMING_PERCENT = 80
private const val PARTIAL_WORD_TIMING_PERCENT = 25

/** How closely lyrics follow the singing, worst to best. */
enum class LyricsTiming {
  /** Untimed text, or timestamps that do not move. */
  PLAIN,

  /** Each line lights up as a whole. */
  LINE,

  /** Some lines move word by word. */
  PARTIAL_WORD,

  /** Nearly every line moves word by word. */
  WORD,
}

/**
 * Whether the provider timed this line's words so the highlight travels through them. Words that
 * all share one stamp are a line split into words, and estimated stamps are the app's own guess.
 */
private fun SyncedLine.hasMovingWords(): Boolean {
  val timed = words.orEmpty().filter { !it.estimated && it.word.isNotBlank() }
  if (timed.size <= 1) return timed.isNotEmpty()
  if (timed.all { it.time == timed.first().time }) return false
  // Tolerate the odd overlap from rounding; words running mostly backwards are broken stamps.
  return timed.zipWithNext().count { (previous, next) -> next.time < previous.time } * 10 <= timed.size
}

@Serializable
data class SyncedLine(
  val time: Int,
  val line: String,
  val words: List<SyncedWord>? = null,
  val translation: String? = null,
  val romanization: String? = null,
  /** When the line stops being sung, if the source says. Lets overlapping voices stay lit. */
  val endTime: Int? = null,
  /** The answering or backing voice, sung under (or over) this line. Drawn small beneath it. */
  val background: SyncedLine? = null,
  /** Second singer of a duet: drawn on the opposite edge from the lead. */
  val alignEnd: Boolean = false,
)

@Serializable
data class SyncedWord(
  val time: Int,
  val word: String,
  val startsNewWord: Boolean = true,
  /** Display-only interpolation must not outrank timestamps supplied by a provider. */
  val estimated: Boolean = false,
)
