/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.lyrics

import java.util.Locale
data class LyricsDraftLine(
  val id: Int,
  val text: String,
  val timeMs: Int? = null,
  val original: SyncedLine? = null,
)

enum class LyricsDraftError {
  EMPTY,
  INCOMPLETE_TIMING,
  OUT_OF_ORDER,
  INVALID_TIME,
}

data class LyricsDraft(
  val lines: List<LyricsDraftLine>,
  val nextId: Int,
) {
  fun editText(index: Int, text: String): LyricsDraft =
    copy(lines = lines.toMutableList().apply { this[index] = this[index].copy(text = text) })

  fun setTime(index: Int, timeMs: Int?): LyricsDraft {
    require(timeMs == null || timeMs >= 0)
    return copy(lines = lines.toMutableList().apply { this[index] = this[index].copy(timeMs = timeMs) })
  }

  fun insertAfter(index: Int): LyricsDraft =
    copy(
      lines = lines.toMutableList().apply { add(index + 1, LyricsDraftLine(nextId, "")) },
      nextId = nextId + 1,
    )

  fun delete(index: Int): LyricsDraft {
    val remaining = lines.toMutableList().apply { removeAt(index) }
    return if (remaining.isEmpty()) {
      copy(lines = listOf(LyricsDraftLine(nextId, "")), nextId = nextId + 1)
    } else {
      copy(lines = remaining)
    }
  }

  fun clearTimes(): LyricsDraft = copy(lines = lines.map { it.copy(timeMs = null) })

  private fun meaningfulLines(): List<LyricsDraftLine> = lines.filter { it.timeMs != null || it.text.isNotBlank() }

  fun hasSameContent(other: LyricsDraft): Boolean =
    meaningfulLines().map { it.copy(id = 0) } == other.meaningfulLines().map { it.copy(id = 0) }

  fun validationError(): LyricsDraftError? {
    val meaningful = meaningfulLines()
    if (meaningful.none { it.text.isNotBlank() }) return LyricsDraftError.EMPTY
    if (meaningful.any { (it.timeMs ?: 0) < 0 }) return LyricsDraftError.INVALID_TIME
    if (meaningful.any { it.timeMs != null }) {
      if (meaningful.any { it.timeMs == null }) return LyricsDraftError.INCOMPLETE_TIMING
      if (meaningful.zipWithNext().any { (first, second) -> requireNotNull(first.timeMs) > requireNotNull(second.timeMs) }) {
        return LyricsDraftError.OUT_OF_ORDER
      }
    }
    return null
  }

  fun toLyrics(): Lyrics {
    require(validationError() == null) { "The lyrics draft must be valid before saving." }
    val meaningful = meaningfulLines()
    val synced = if (meaningful.any { it.timeMs != null }) {
      meaningful.map { line ->
        val time = requireNotNull(line.timeMs)
        val original = line.original
        if (original == null) {
          SyncedLine(time = time, line = line.text)
        } else {
          val shifted = shiftLine(original, time.toLong() - original.time)
          val textUnchanged = line.text == original.line
          shifted.copy(
            line = line.text,
            words = shifted.words.takeIf { textUnchanged },
            translation = shifted.translation.takeIf { textUnchanged },
            romanization = shifted.romanization.takeIf { textUnchanged },
          )
        }
      }
    } else {
      null
    }
    return Lyrics(
      plain = meaningful.map { it.text },
      synced = synced,
      areFromRemote = false,
      sourceType = LyricsSourceType.LOCAL,
    )
  }

  companion object {
    private val TIME_PATTERN = Regex("""(\d{1,5}):([0-5]\d)(?:[.:](\d{1,3}))?""")

    fun from(lyrics: Lyrics?): LyricsDraft {
      val synced = lyrics?.synced.orEmpty()
      val lines = if (synced.isNotEmpty()) {
        synced.mapIndexed { index, line -> LyricsDraftLine(index, line.line, line.time, line) }
      } else {
        lyrics?.plain.orEmpty().mapIndexed { index, text -> LyricsDraftLine(index, text) }
      }.ifEmpty { listOf(LyricsDraftLine(0, "")) }
      return LyricsDraft(lines, lines.size)
    }

    fun formatTime(timeMs: Int): String {
      require(timeMs >= 0)
      return String.format(Locale.ROOT, "%02d:%02d.%03d", timeMs / 60_000, timeMs / 1000 % 60, timeMs % 1000)
    }

    fun parseTime(text: String): Int? {
      val match = TIME_PATTERN.matchEntire(text.trim().removeSurrounding("[", "]")) ?: return null
      val minutes = match.groupValues[1].toLong()
      val seconds = match.groupValues[2].toLong()
      val fraction = match.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').toLong()
      val millis = minutes * 60_000 + seconds * 1000 + fraction
      return millis.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun shiftLine(line: SyncedLine, delta: Long): SyncedLine {
      fun shift(time: Int): Int = (time.toLong() + delta).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
      return line.copy(
        time = shift(line.time),
        words = line.words?.map { it.copy(time = shift(it.time)) },
        endTime = line.endTime?.let(::shift),
        background = line.background?.let { shiftLine(it, delta) },
      )
    }
  }
}
