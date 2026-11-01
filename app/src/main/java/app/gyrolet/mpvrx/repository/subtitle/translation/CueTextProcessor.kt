/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

/**
 * Turns cue lines into plain text a translation engine can't damage, and back.
 *
 * Markup at the start/end of a cue (`{\an8}`, `<i>`, `♪`, dialogue dashes) is peeled off and
 * re-attached untouched. Markup in the middle becomes numbered tokens (`⟦0⟧`); tokens an engine
 * drops are re-inserted at the nearest edge instead of being lost.
 */
class CueTextProcessor(
  private val format: SubtitleFormat,
) {
  /** Text sent to the engine, plus what's needed to rebuild the cue. */
  class Unit(
    val text: String,
    internal val prefix: String,
    internal val suffix: String,
    internal val tokens: List<String>,
  )

  class Plan(
    val cue: SubtitleCue,
    /** One unit per line for dialogue cues ("- Hi\n- Hello"), otherwise a single unit. */
    val units: List<Unit>,
    private val perLine: Boolean,
  ) {
    internal val joinedLineCount: Int get() = if (perLine) units.size else cue.lines.size
    internal val isPerLine: Boolean get() = perLine
  }

  private val tagPattern =
    when (format) {
      SubtitleFormat.ASS -> Regex("""\{[^}]*\}|\\[nh]""")
      SubtitleFormat.SRT, SubtitleFormat.VTT -> Regex("""<[^>\n]*>|\{\\[^}\n]*\}|&(?:amp|lt|gt|nbsp|lrm|rlm);""")
    }
  private val tokenPattern = Regex("""⟦\s*(\d+)\s*⟧""")
  private val edgeChars = setOf(' ', '\t', '\u00A0', '♪', '♫', '#', '*')
  private val dashes = setOf('-', '–', '—')

  fun plan(cue: SubtitleCue): Plan {
    val lines = cue.lines.map(String::trim).filter(String::isNotEmpty)
    val dialogue = lines.size >= 2 && lines.all { line -> stripTags(line).trimStart().firstOrNull() in dashes }
    return if (dialogue) {
      Plan(cue, lines.map(::protect), perLine = true)
    } else {
      Plan(cue, listOf(protect(lines.joinToString(" "))), perLine = false)
    }
  }

  /** The unit's text with inline markup removed, for engines that don't keep placeholders. */
  fun plainText(unit: Unit): String =
    tokenPattern
      .replace(unit.text) { match ->
        when (unit.tokens.getOrNull(match.groupValues[1].toInt())) {
          "&amp;" -> "&"
          "&nbsp;", "\\h", "\\n" -> " "
          else -> ""
        }
      }.replace(Regex("""\s+"""), " ")
      .trim()

  /** True when there's nothing worth sending (only tags, music notes, punctuation or numbers). */
  fun isTranslatable(unit: Unit): Boolean = unit.text.replace(tokenPattern, "").any(Char::isLetter)

  /** Rebuilds the cue's lines from translated unit texts (same order as [Plan.units]). */
  fun rebuild(
    plan: Plan,
    translated: List<String>,
    rtl: Boolean,
  ): List<String> {
    val lines =
      if (plan.isPerLine) {
        plan.units.mapIndexed { i, unit -> restore(unit, cleanup(translated[i])) }
      } else {
        val unit = plan.units.single()
        val body = cleanup(translated.single())
        val targetLines = wrapLineCount(plan.joinedLineCount, visibleLength(body))
        val bodyLines = wrap(body, targetLines)
        restoreAcrossLines(unit, bodyLines)
      }
    return if (rtl && format != SubtitleFormat.ASS) lines.map(::embedRtl) else lines
  }

  internal fun protect(raw: String): Unit {
    var start = 0
    var end = raw.length
    // Peel leading markup, decorations and one dialogue dash.
    while (start < end) {
      val tag = tagPattern.matchAt(raw, start)
      when {
        tag != null && tag.range.last < end -> start = tag.range.last + 1
        raw[start] in edgeChars -> start++
        else -> break
      }
    }
    if (start < end && raw[start] in dashes) {
      start++
      while (start < end && raw[start] in edgeChars) start++
    }
    // Peel trailing markup and decorations.
    while (end > start) {
      val tag = tagPattern.findAll(raw.substring(start, end)).lastOrNull()
      when {
        tag != null && start + tag.range.last + 1 == end -> end = start + tag.range.first
        raw[end - 1] in edgeChars -> end--
        else -> break
      }
    }
    val tokens = mutableListOf<String>()
    val body =
      tagPattern.replace(raw.substring(start, end)) { match ->
        tokens += match.value
        "⟦${tokens.lastIndex}⟧"
      }
    return Unit(body.replace(Regex("""\s+"""), " ").trim(), raw.substring(0, start), raw.substring(end), tokens)
  }

  private fun restore(
    unit: Unit,
    body: String,
  ): String = restoreAcrossLines(unit, listOf(body)).single()

  private fun restoreAcrossLines(
    unit: Unit,
    bodyLines: List<String>,
  ): List<String> {
    val used = BooleanArray(unit.tokens.size)
    val restored =
      bodyLines
        .map { line ->
          tokenPattern.replace(line) { match ->
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index in unit.tokens.indices && !used[index]) {
              used[index] = true
              unit.tokens[index]
            } else {
              ""
            }
          }.replace(Regex(""" {2,}"""), " ").trim()
        }.toMutableList()
    // Tokens the engine dropped: closing tags go to the end, everything else to the start.
    // Entities and hard spaces only stood for characters, so they aren't worth re-adding.
    val missing = unit.tokens.indices.filterNot { used[it] }.map { unit.tokens[it] }.filterNot(::isCharacterToken)
    if (missing.isNotEmpty()) {
      // "<i>one</i> <i>two</i>" joined: the inner "</i> <i>" is redundant once the edges carry the same
      // tags, and "KATE: <i>one</i> <i>two</i>" needs a single "<i>" at the start, not one per line.
      val (closing, opening) =
        missing
          .partition(::isClosingTag)
          .let { (close, open) ->
            close.distinct().filterNot { it in unit.suffix } to open.distinct().filterNot { it in unit.prefix }
          }
      restored[0] = opening.joinToString("") + restored[0]
      restored[restored.lastIndex] = restored.last() + closing.joinToString("")
    }
    restored[0] = unit.prefix + restored[0]
    restored[restored.lastIndex] = restored.last() + unit.suffix
    return restored
  }

  private fun isCharacterToken(token: String): Boolean = token.startsWith("&") || token == "\\h" || token == "\\n"

  private fun isClosingTag(tag: String): Boolean =
    tag.startsWith("</") || Regex("""\\[ibus]0\b""").containsMatchIn(tag)

  /** Removes artifacts engines tend to add: wrapping quotes, stray brackets around tokens. */
  private fun cleanup(text: String): String =
    text
      .replace(Regex("""\s+"""), " ")
      .replace(Regex("""⟦\s*(\d+)\s*⟧"""), "⟦$1⟧")
      .trim()

  private fun visibleLength(text: String): Int = text.replace(tokenPattern, "").length

  private fun wrapLineCount(
    originalLines: Int,
    length: Int,
  ): Int =
    when {
      originalLines <= 1 || length <= MAX_LINE_LENGTH -> 1
      originalLines >= 3 && length > MAX_LINE_LENGTH * 2 -> 3
      else -> 2
    }

  /** Splits at spaces so line lengths are as even as possible. */
  internal fun wrap(
    text: String,
    lineCount: Int,
  ): List<String> {
    if (lineCount <= 1) return listOf(text)
    val words = text.split(' ').filter(String::isNotEmpty)
    if (words.size < lineCount) return listOf(text)
    val lines = mutableListOf<String>()
    var remaining = words
    for (line in lineCount downTo 2) {
      val remainingLength = remaining.sumOf { visibleLength(it) + 1 } - 1
      val target = remainingLength / line
      var bestCut = 1
      var bestDiff = Int.MAX_VALUE
      var length = -1
      for (cut in 1..remaining.size - (line - 1)) {
        length += visibleLength(remaining[cut - 1]) + 1
        val diff = kotlin.math.abs(length - target)
        if (diff < bestDiff) {
          bestDiff = diff
          bestCut = cut
        }
      }
      lines += remaining.subList(0, bestCut).joinToString(" ")
      remaining = remaining.subList(bestCut, remaining.size)
    }
    lines += remaining.joinToString(" ")
    return lines
  }

  /**
   * Wraps a line in a right-to-left embedding so leading/trailing punctuation renders on the
   * correct side. Tags stay outside the embedding so the renderer still sees them.
   */
  private fun embedRtl(line: String): String {
    var start = 0
    while (true) {
      val tag = tagPattern.matchAt(line, start) ?: break
      start = tag.range.last + 1
    }
    var end = line.length
    while (end > start) {
      val tag = tagPattern.findAll(line.substring(start, end)).lastOrNull() ?: break
      if (start + tag.range.last + 1 != end) break
      end = start + tag.range.first
    }
    if (start >= end) return line
    return line.substring(0, start) + RLE + line.substring(start, end) + PDF + line.substring(end)
  }

  private fun stripTags(text: String): String = text.replace(tagPattern, "")

  companion object {
    const val MAX_LINE_LENGTH = 42
    private const val RLE = '\u202B'
    private const val PDF = '\u202C'
  }
}
