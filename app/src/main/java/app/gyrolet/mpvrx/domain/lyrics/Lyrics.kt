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
  fun isValid(): Boolean = !synced.isNullOrEmpty() || !plain.isNullOrEmpty()
}

@Serializable
data class SyncedLine(
  val time: Int,
  val line: String,
  val words: List<SyncedWord>? = null,
  val translation: String? = null,
  val romanization: String? = null,
)

@Serializable
data class SyncedWord(
  val time: Int,
  val word: String,
  val startsNewWord: Boolean = true,
)
