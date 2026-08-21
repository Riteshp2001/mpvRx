/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import java.util.regex.Pattern

object LyricsUtils {

  private val LRC_LINE_REGEX = Pattern.compile("^\\[(\\d{1,2}):(\\d{2})[.:](\\d{2,3})](.*)$")
  private val LRC_WORD_REGEX = Pattern.compile("<(\\d{1,2}):(\\d{2})[.:](\\d{2,3})>([^<]*)")
  private val LRC_WORD_TAG_REGEX = Regex("<\\d{1,2}:\\d{2}[.:]\\d{2,3}>")
  private val LRC_WORD_SPLIT_REGEX = Regex("(?=<\\d{1,2}:\\d{2}[.:]\\d{2,3}>)")
  private val LRC_METADATA_PATTERN = Pattern.compile("^\\[[a-zA-Z]+:.*]$")
  private val LYRIC_WORD_REGEX = Regex("\\S+")

  fun parseLyrics(
    lyricsText: String?,
    sourceType: LyricsSourceType = LyricsSourceType.ONLINE,
  ): Lyrics {
    if (lyricsText.isNullOrBlank()) {
      return Lyrics(plain = emptyList(), synced = emptyList(), sourceType = sourceType)
    }

    val syncedLines = mutableListOf<SyncedLine>()
    val plainLines = mutableListOf<String>()
    var isSynced = false

    lyricsText.lines().forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isEmpty() || LRC_METADATA_PATTERN.matcher(line).matches()) return@forEach

      val lineMatcher = LRC_LINE_REGEX.matcher(line)
      if (lineMatcher.matches()) {
        isSynced = true
        val minutes = lineMatcher.group(1)?.toLong() ?: 0
        val seconds = lineMatcher.group(2)?.toLong() ?: 0
        val fraction = lineMatcher.group(3)?.toLong() ?: 0
        val rawText = lineMatcher.group(4)?.trim() ?: ""

        val millis = if (lineMatcher.group(3)?.length == 2) fraction * 10 else fraction
        val lineTimestamp = (minutes * 60 * 1000 + seconds * 1000 + millis).toInt()

        val displayText = LRC_WORD_TAG_REGEX.replace(rawText, "").trim()

        val words = mutableListOf<SyncedWord>()
        if (rawText.contains(LRC_WORD_TAG_REGEX)) {
          val parts = rawText.split(LRC_WORD_SPLIT_REGEX)
          for (part in parts) {
            if (part.isEmpty()) continue
            val wordMatcher = LRC_WORD_REGEX.matcher(part)
            if (wordMatcher.find()) {
              val wMin = wordMatcher.group(1)?.toLong() ?: 0
              val wSec = wordMatcher.group(2)?.toLong() ?: 0
              val wFrac = wordMatcher.group(3)?.toLong() ?: 0
              val wText = (wordMatcher.group(4) ?: "").trim()
              val wMillis = if (wordMatcher.group(3)?.length == 2) wFrac * 10 else wFrac
              val wTime = (wMin * 60 * 1000 + wSec * 1000 + wMillis).toInt()

              if (wText.isNotEmpty()) {
                words.add(SyncedWord(time = wTime, word = wText, startsNewWord = words.isEmpty()))
              }
            }
          }
        }

        val (finalLine, inlineTranslation) = when {
          displayText.contains("\n") -> {
            val p = displayText.split("\n", limit = 2)
            Pair(p[0].trim(), p.getOrNull(1)?.trim())
          }
          displayText.contains(" / ") -> {
            val p = displayText.split(" / ", limit = 2)
            Pair(p[0].trim(), p.getOrNull(1)?.trim())
          }
          displayText.contains(" | ") -> {
            val p = displayText.split(" | ", limit = 2)
            Pair(p[0].trim(), p.getOrNull(1)?.trim())
          }
          else -> Pair(displayText, null)
        }

        val lastLine = syncedLines.lastOrNull()
        if (lastLine != null && lastLine.time == lineTimestamp && lastLine.translation == null) {
          syncedLines[syncedLines.lastIndex] = lastLine.copy(translation = finalLine)
        } else {
          syncedLines.add(
            SyncedLine(
              time = lineTimestamp,
              line = finalLine,
              translation = inlineTranslation,
              words = if (words.isNotEmpty()) words else null,
            ),
          )
        }
      } else {
        if (!isSynced) {
          plainLines.add(line)
        } else if (syncedLines.isNotEmpty()) {
          val last = syncedLines[syncedLines.lastIndex]
          if (last.translation.isNullOrBlank()) {
            syncedLines[syncedLines.lastIndex] = last.copy(translation = line)
          } else {
            syncedLines[syncedLines.lastIndex] = last.copy(line = "${last.line}\n$line")
          }
        }
      }
    }

    return if (isSynced && syncedLines.isNotEmpty()) {
      val sorted = syncedLines.sortedBy { it.time }
      val wordSynced =
        sorted.mapIndexed { index, line ->
          if (!line.words.isNullOrEmpty() || line.line.isBlank()) {
            line
          } else {
            line.copy(
              words = estimateWordTimings(line, sorted.getOrNull(index + 1)?.time),
            )
          }
        }
      val plainVersion = wordSynced.map { it.line }
      Lyrics(
        synced = wordSynced,
        plain = plainVersion,
        areFromRemote = (sourceType == LyricsSourceType.ONLINE),
        sourceType = sourceType,
      )
    } else {
      Lyrics(
        plain = plainLines,
        synced = null,
        areFromRemote = (sourceType == LyricsSourceType.ONLINE),
        sourceType = sourceType,
      )
    }
  }

  private fun estimateWordTimings(
    line: SyncedLine,
    nextLineTimeMs: Int?,
  ): List<SyncedWord>? {
    val words = LYRIC_WORD_REGEX.findAll(line.line).map { it.value }.toList()
    if (words.isEmpty()) return null

    val weights = words.map { word -> word.count(Char::isLetterOrDigit).coerceAtLeast(1) }
    val totalWeight = weights.sum().coerceAtLeast(1)
    val naturalDurationMs = (totalWeight * 90L).coerceIn(1_200L, 6_000L)
    val availableDurationMs =
      nextLineTimeMs
        ?.takeIf { it > line.time }
        ?.let { (it - line.time).toLong().coerceAtMost(8_000L) }
        ?: naturalDurationMs

    var elapsedWeight = 0
    return words.mapIndexed { index, word ->
      val wordTimeMs = line.time.toLong() + availableDurationMs * elapsedWeight / totalWeight
      elapsedWeight += weights[index]
      SyncedWord(
        time = wordTimeMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        word = word,
        startsNewWord = true,
      )
    }
  }

  fun getActiveLineIndex(
    syncedLines: List<SyncedLine>?,
    positionMs: Long,
    offsetMs: Int = 0,
  ): Int {
    if (syncedLines.isNullOrEmpty()) return -1
    val targetTime = positionMs + offsetMs
    var activeIndex = -1
    for (i in syncedLines.indices) {
      if (syncedLines[i].time <= targetTime) {
        activeIndex = i
      } else {
        break
      }
    }
    return activeIndex
  }
}
