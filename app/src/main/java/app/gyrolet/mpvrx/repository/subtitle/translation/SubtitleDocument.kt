/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import java.util.Locale

enum class SubtitleFormat(
  /** Line break used inside one cue's text. */
  val lineBreak: String,
) {
  SRT("\n"),
  VTT("\n"),
  ASS("\\N"),
  ;

  companion object {
    fun fromExtension(extension: String): SubtitleFormat? =
      when (extension.lowercase(Locale.ROOT).removePrefix(".")) {
        "srt" -> SRT
        "vtt", "webvtt" -> VTT
        "ass", "ssa" -> ASS
        else -> null
      }

    /** For files whose extension lies (or is missing), look at the content. */
    fun sniff(content: String): SubtitleFormat? {
      val head = content.trimStart('﻿', ' ', '\n', '\r', '\t').take(512)
      return when {
        head.startsWith("WEBVTT") -> VTT
        head.startsWith("[Script Info]", ignoreCase = true) -> ASS
        Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(head) -> SRT
        else -> null
      }
    }
  }
}

/** One translatable piece of text: everything else in the file is kept byte-for-byte. */
data class SubtitleCue(
  val index: Int,
  val lines: List<String>,
)

/**
 * A subtitle file split into literal text (headers, numbers, timestamps, styles) and cue text.
 * [render] with no replacements reproduces the original file exactly, so timing, numbering,
 * styles and positioning can't be damaged by translation.
 */
class SubtitleDocument private constructor(
  val format: SubtitleFormat,
  private val segments: List<Segment>,
  private val crlf: Boolean,
) {
  private sealed interface Segment {
    data class Literal(
      val text: String,
    ) : Segment

    data class Text(
      val cue: SubtitleCue,
    ) : Segment
  }

  val cues: List<SubtitleCue> = segments.filterIsInstance<Segment.Text>().map { it.cue }

  /** @param replacement translated lines for a cue, or null to keep the original. */
  fun render(replacement: (SubtitleCue) -> List<String>? = { null }): String {
    val out = StringBuilder()
    for (segment in segments) {
      when (segment) {
        is Segment.Literal -> out.append(segment.text)
        is Segment.Text -> out.append((replacement(segment.cue) ?: segment.cue.lines).joinToString(format.lineBreak))
      }
    }
    return if (crlf) out.toString().replace("\n", "\r\n") else out.toString()
  }

  companion object {
    private val vttMetaBlock = Regex("""^(WEBVTT|NOTE|STYLE|REGION)\b""")
    private val assDrawing = Regex("""\{[^}]*\\p[1-9][^}]*\}""")
    private val assTags = Regex("""\{[^}]*\}|\\[nNh]""")

    /**
     * Points every ASS style (and inline `\fn` override) at [font]. Typesetting fonts such as
     * Arial or a fansub's display font usually have no Arabic glyphs, so translated lines would
     * render as boxes or in a random fallback.
     */
    fun withAssFont(
      content: String,
      font: String,
    ): String {
      val crlf = content.contains("\r\n")
      var inStyles = false
      var fontIndex = 1
      val lines =
        content.replace("\r\n", "\n").split('\n').map { line ->
          val trimmed = line.trimStart()
          if (trimmed.startsWith("[")) inStyles = trimmed.contains("Styles]", ignoreCase = true)
          when {
            inStyles && trimmed.startsWith("Format:", ignoreCase = true) -> {
              fontIndex =
                trimmed.substringAfter(':').split(',').indexOfFirst { it.trim().equals("Fontname", ignoreCase = true) }.takeIf { it >= 0 } ?: 1
              line
            }
            inStyles && trimmed.startsWith("Style:", ignoreCase = true) -> {
              val prefix = line.substringBefore(':') + ":"
              val fields = line.substringAfter(':').split(',').toMutableList()
              if (fontIndex < fields.size) {
                val leading = fields[fontIndex].takeWhile(Char::isWhitespace)
                fields[fontIndex] = leading + font
              }
              prefix + fields.joinToString(",")
            }
            trimmed.startsWith("Dialogue:", ignoreCase = true) -> line.replace(Regex("""\\fn[^\\}]*"""), Regex.escapeReplacement("\\fn$font"))
            else -> line
          }
        }
      val joined = lines.joinToString("\n")
      return if (crlf) joined.replace("\n", "\r\n") else joined
    }

    fun parse(
      content: String,
      format: SubtitleFormat,
    ): SubtitleDocument {
      val crlf = content.contains("\r\n")
      val normalized = content.replace("\r\n", "\n")
      return when (format) {
        SubtitleFormat.SRT, SubtitleFormat.VTT -> parseBlocks(normalized, format, crlf)
        SubtitleFormat.ASS -> parseAss(normalized, crlf)
      }
    }

    private class Builder {
      val segments = mutableListOf<Segment>()
      private val literal = StringBuilder()
      private var cueIndex = 0

      fun literal(text: String) {
        literal.append(text)
      }

      fun cue(lines: List<String>) {
        flush()
        segments += Segment.Text(SubtitleCue(cueIndex++, lines))
      }

      fun flush() {
        if (literal.isNotEmpty()) segments += Segment.Literal(literal.toString())
        literal.clear()
      }
    }

    /** SRT and WebVTT: blank-line separated blocks; text follows the `-->` timing line. */
    private fun parseBlocks(
      text: String,
      format: SubtitleFormat,
      crlf: Boolean,
    ): SubtitleDocument {
      val lines = text.split('\n')
      val last = lines.lastIndex
      val builder = Builder()
      var i = 0
      while (i < lines.size) {
        if (lines[i].isBlank()) {
          builder.literal(lines[i])
          if (i < last) builder.literal("\n")
          i++
          continue
        }
        var end = i
        while (end < lines.size && lines[end].isNotBlank()) end++
        val block = lines.subList(i, end)
        val timing = block.indexOfFirst { it.contains("-->") }
        val isMeta = format == SubtitleFormat.VTT && vttMetaBlock.containsMatchIn(block.first())
        if (timing < 0 || isMeta || timing == block.lastIndex) {
          for (k in i until end) {
            builder.literal(lines[k])
            if (k < last) builder.literal("\n")
          }
        } else {
          for (k in i..i + timing) {
            builder.literal(lines[k])
            builder.literal("\n")
          }
          builder.cue(block.subList(timing + 1, block.size).toList())
          if (end - 1 < last) builder.literal("\n")
        }
        i = end
      }
      builder.flush()
      return SubtitleDocument(format, builder.segments, crlf)
    }

    /** ASS/SSA: only the Text field of `Dialogue:` events is translatable. */
    private fun parseAss(
      text: String,
      crlf: Boolean,
    ): SubtitleDocument {
      val lines = text.split('\n')
      val builder = Builder()
      var inEvents = false
      var fieldCount = 10
      lines.forEachIndexed { index, line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("[")) inEvents = trimmed.startsWith("[Events]", ignoreCase = true)
        if (inEvents && trimmed.startsWith("Format:", ignoreCase = true)) {
          fieldCount = trimmed.substringAfter(':').split(',').size.coerceAtLeast(1)
        }
        val textStart = if (inEvents && trimmed.startsWith("Dialogue:", ignoreCase = true)) textFieldStart(line, fieldCount) else -1
        val dialogueText = if (textStart >= 0) line.substring(textStart) else null
        if (dialogueText != null && isTranslatableAss(dialogueText)) {
          builder.literal(line.substring(0, textStart))
          builder.cue(dialogueText.split("\\N"))
        } else {
          builder.literal(line)
        }
        if (index < lines.lastIndex) builder.literal("\n")
      }
      builder.flush()
      return SubtitleDocument(SubtitleFormat.ASS, builder.segments, crlf)
    }

    /** The Text field starts after the (fieldCount - 1)th comma following `Dialogue:`. */
    private fun textFieldStart(
      line: String,
      fieldCount: Int,
    ): Int {
      var pos = line.indexOf(':')
      repeat(fieldCount - 1) {
        pos = line.indexOf(',', pos + 1)
        if (pos < 0) return -1
      }
      return pos + 1
    }

    private fun isTranslatableAss(text: String): Boolean {
      if (assDrawing.containsMatchIn(text)) return false
      return text.replace(assTags, "").any(Char::isLetter)
    }
  }
}
