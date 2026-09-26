/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Parsing shared by the online providers: JSON envelopes, QQ/NetEase karaoke
 * ranges and plain LRC. Derived from BitChord's lyrics parsers
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import app.gyrolet.mpvrx.utils.media.LyricsUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A break worth interrupting the line for. Anything shorter just reads as pacing. */
internal const val MIN_GAP_MS = 4_000

/** A line with the stamp at which it stops being sung, used to find the silences. */
internal data class TimedLine(
  val line: SyncedLine,
  val endMs: Int,
)

/** Wraps lines fetched from a provider into a valid [Lyrics], or null when there is nothing to show. */
internal fun lyricsFromSynced(lines: List<SyncedLine>): Lyrics? {
  if (lines.none { it.line.isNotBlank() }) return null
  val sorted = lines.sortedBy { it.time }
  return Lyrics(
    plain = sorted.map { it.line },
    synced = sorted,
    areFromRemote = true,
    sourceType = LyricsSourceType.ONLINE,
  )
}

/** Wraps unsynced text, split into the lines a plainlyrics panel scrolls. */
internal fun lyricsFromPlain(text: String): Lyrics? {
  val lines =
    text
      .lines()
      .map { it.trim().trimEnd('\r') }
      .filter { it.isNotEmpty() }
      .filterNot { LRC_METADATA.matches(it) }
  if (lines.isEmpty()) return null
  return Lyrics(
    plain = lines,
    synced = null,
    areFromRemote = true,
    sourceType = LyricsSourceType.ONLINE,
  )
}

/**
 * Reads whatever a provider handed back — a JSON envelope, TTML, karaoke
 * ranges, ordinary LRC or plain text — and returns the best lyrics it holds.
 *
 * Null means "this is not lyrics", which is deliberately the same answer a
 * provider gives for a track it does not have: an error page must not be able
 * to masquerade as a hit.
 */
internal fun parseProviderText(raw: String): Lyrics? {
  val content = unwrapEnvelope(raw) ?: return null
  return when {
    TtmlLyrics.looksLike(content) -> TtmlLyrics.parse(content)?.let(::lyricsFromSynced)
    KaraokeLrc.looksLike(content) -> lyricsFromSynced(KaraokeLrc.parse(content))
    content.trimStart().startsWith("<") -> null
    else -> parseLrcText(content)
  }
}

/** LRC and plain text both go through the app's own reader, which already knows both shapes. */
internal fun parseLrcText(text: String): Lyrics? {
  val parsed = LyricsUtils.parseLyrics(text, sourceType = LyricsSourceType.ONLINE)
  return parsed.takeIf { it.isValid() }
}

/**
 * Providers sometimes wrap the same lyric string in one or two JSON envelopes,
 * occasionally fenced as though it were a code block. Unwrap down to the text.
 */
internal fun unwrapEnvelope(raw: String): String? {
  var value = raw.trim()
  if (value.startsWith("```")) {
    value =
      value
        .lineSequence()
        .drop(1)
        .toList()
        .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
        .joinToString("\n")
        .trim()
  }
  if (value.isBlank()) return null
  val json = runCatching { lyricsJson.parseToJsonElement(value) }.getOrNull() ?: return value
  return extractText(json)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun extractText(element: JsonElement): String? =
  when (element) {
    JsonNull -> null
    is JsonPrimitive ->
      if (element.isString) {
        val text = element.content.trim()
        val nested = runCatching { lyricsJson.parseToJsonElement(text) }.getOrNull()
        if (nested != null && nested !is JsonPrimitive) extractText(nested) else text
      } else {
        null
      }
    is JsonArray -> element.mapNotNull(::extractText).joinToString("\n").takeIf { it.isNotBlank() }
    is JsonObject -> {
      if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false") {
        null
      } else {
        CONTENT_KEYS
          .asSequence()
          .mapNotNull { key -> element[key]?.let(::extractText) }
          .firstOrNull()
          ?: (element["metadata"] as? JsonObject)?.let(::extractText)
          ?: element["words"]?.let(::extractText)
      }
    }
  }

internal fun unescapeTtml(value: String): String =
  if (value.contains("&lt;tt", ignoreCase = true)) {
    value
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&apos;", "'")
      .replace("&amp;", "&")
  } else {
    value
  }

/**
 * Marks the instrumental stretches with blank lines, the way an LRC file marks
 * them with a bare timestamp, so a long break shows a gap rather than a line
 * that sits there for forty seconds.
 */
internal fun List<TimedLine>.withInstrumentalGaps(): List<SyncedLine> {
  if (isEmpty()) return map { it.line }
  val sorted = sortedBy { it.line.time }
  val out = ArrayList<SyncedLine>(sorted.size + 4)
  if (sorted.first().line.time >= MIN_GAP_MS) out += SyncedLine(time = 0, line = "")
  sorted.forEachIndexed { index, timed ->
    out += timed.line
    val next = sorted.getOrNull(index + 1) ?: return@forEachIndexed
    val silence = next.line.time - timed.endMs
    // A marker sharing its own line's stamp could never be reached: the cursor
    // takes the last line whose stamp has passed, so the note would sit on top
    // of the line it belongs to and its words would never light up.
    if (silence >= MIN_GAP_MS && timed.endMs > timed.line.time) {
      out += SyncedLine(time = timed.endMs, line = "")
    }
  }
  return out
}

/** HTML entities arrive in the word timings of every community-hosted source. */
internal fun decodeEntities(text: String): String {
  if ('&' !in text) return text
  return text
    .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
      match.groupValues[1]
        .toIntOrNull(16)
        ?.toChar()
        ?.toString() ?: match.value
    }.replace(Regex("""&#(\d+);""")) { match ->
      match.groupValues[1]
        .toIntOrNull()
        ?.toChar()
        ?.toString() ?: match.value
    }.replace("&apos;", "'")
    .replace("&quot;", "\"")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
}

private val LRC_METADATA = Regex("""^\[[a-zA-Z]+:.*]$""")

private val CONTENT_KEYS =
  listOf(
    "ttml",
    "ttmlContent",
    "lyrics",
    "lrc",
    "content",
    "text",
    "plainLyrics",
    "syncedLyrics",
    "richSyncLyrics",
    "line",
    "lines",
    "lyric",
    "data",
    "result",
    "response",
  )

/**
 * QQ/QRC and NetEase YRC: `[startMs,durationMs]line` carrying a
 * `(startMs,durationMs)` range in front of each word.
 */
internal object KaraokeLrc {
  private val LINE = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
  private val PREFIX_WORD = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
  private val SUFFIX_WORD = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
  private val WORD_TIME = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
  private val CONTENT = Regex("""LyricContent\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

  fun looksLike(raw: String): Boolean =
    lyricContent(raw).lineSequence().any { line ->
      LINE.matchEntire(line.trim())?.groupValues?.get(3)?.let {
        PREFIX_WORD.containsMatchIn(it) || SUFFIX_WORD.containsMatchIn(it)
      } == true
    }

  fun parse(raw: String): List<SyncedLine> =
    lyricContent(raw)
      .lineSequence()
      .mapNotNull { source ->
        val match = LINE.matchEntire(source.trim()) ?: return@mapNotNull null
        val lineStart = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val lineDuration = match.groupValues[2].toIntOrNull() ?: 0
        val body = match.groupValues[3]
        val prefixed =
          PREFIX_WORD
            .findAll(body)
            .mapNotNull { word ->
              timedWord(word.groupValues[3], word.groupValues[1])
            }.toList()
        val suffixed =
          SUFFIX_WORD
            .findAll(body)
            .mapNotNull { word ->
              timedWord(word.groupValues[1], word.groupValues[3])
            }.toList()
        val words =
          if (prefixed.sumOf { it.word.length } >= suffixed.sumOf { it.word.length }) {
            prefixed
          } else {
            suffixed
          }
        if (words.isEmpty()) return@mapNotNull null
        SyncedLine(
          time = minOf(lineStart, words.first().time),
          line = decodeEntities(body.replace(WORD_TIME, "")).trim(),
          words = words,
        ).let { it to (lineStart + lineDuration).coerceAtLeast(it.time) }
      }.filter { (line, _) -> line.line.isNotBlank() }
      .map { (line, end) -> TimedLine(line, end) }
      .toList()
      .withInstrumentalGaps()

  private fun timedWord(
    text: String,
    start: String,
  ): SyncedWord? {
    val clean = decodeEntities(text).trim()
    if (clean.isEmpty()) return null
    val startMs = start.toIntOrNull() ?: return null
    return SyncedWord(time = startMs, word = clean, startsNewWord = true)
  }

  /** Some hosts wrap the whole document in a `LyricContent = "..."` literal. */
  private fun lyricContent(raw: String): String =
    CONTENT
      .find(raw)
      ?.groupValues
      ?.get(1)
      ?.replace("&quot;", "\"")
      ?.replace("&apos;", "'")
      ?.replace("&lt;", "<")
      ?.replace("&gt;", ">")
      ?.replace("&amp;", "&") ?: raw
}
