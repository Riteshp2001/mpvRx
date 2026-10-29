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

  fun hasWordTiming(): Boolean = synced.orEmpty().any { line ->
    line.words.orEmpty().any { !it.estimated } || line.background?.words.orEmpty().any { !it.estimated }
  }
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
