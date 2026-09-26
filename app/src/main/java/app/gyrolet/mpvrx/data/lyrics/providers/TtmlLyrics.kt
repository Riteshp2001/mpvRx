/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Apple Music's word-timed lyric format, parsed for the providers that serve
 * it. Derived from BitChord's TTML reader
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A document is one `<p>` per sung line, each holding a `<span>` per syllable
 * with its own `begin` and `end`:
 *
 * ```xml
 * <p begin="27.395" end="28.960" ttm:agent="v1">
 *   <span begin="27.395" end="27.549">I</span>
 *   <span begin="27.549" end="27.740">been</span>
 * </p>
 * ```
 *
 * Syllables of one word are written as adjacent spans with no whitespace
 * between them ("e" + "nough"), so whitespace — not the span boundary — is what
 * separates words. That is the whole trick to reading this format.
 */
internal object TtmlLyrics {
  /** Roles that are not this line at all: alternate renderings of the same words. */
  private val SKIPPED_ROLES = setOf("x-translation", "x-roman")

  fun looksLike(ttml: String): Boolean =
    unescapeTtml(ttml).let { content ->
      content.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
        content.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }

  fun parse(ttml: String): List<SyncedLine>? =
    runCatching {
      val factory =
        DocumentBuilderFactory.newInstance().apply {
          // The document declares four namespaces and attributes are addressed by
          // their qualified names (ttm:agent), so prefixes are left intact.
          isNamespaceAware = false
          // Lyrics arrive from a third-party host; nothing it asks for is fetched.
          // Every one of these is optional and Android's Expat-backed factory
          // rejects some of the Apache names outright, so each is attempted on its
          // own and the hardening is whatever the parser in hand agrees to.
          harden("http://apache.org/xml/features/disallow-doctype-decl")
          harden("http://xml.org/sax/features/external-general-entities", false)
          harden("http://xml.org/sax/features/external-parameter-entities", false)
          harden(XMLConstants.FEATURE_SECURE_PROCESSING)
          runCatching { isExpandEntityReferences = false }
        }
      val document = factory.newDocumentBuilder().parse(InputSource(StringReader(unescapeTtml(ttml))))
      val paragraphs = document.getElementsByTagName("p")
      val lines = ArrayList<TimedLine>(paragraphs.length)
      for (i in 0 until paragraphs.length) {
        val paragraph = paragraphs.item(i) as? Element ?: continue
        lineFrom(paragraph)?.let(lines::add)
      }
      lines.withInstrumentalGaps()
    }.getOrNull()?.takeIf { lines -> lines.any { it.line.isNotBlank() } }

  /** One optional parser feature, set if this parser has it. */
  private fun DocumentBuilderFactory.harden(
    feature: String,
    value: Boolean = true,
  ) {
    runCatching { setFeature(feature, value) }
  }

  /**
   * An attribute named with a prefix, read the same way on both DOM
   * implementations this runs on: the JVM's keeps `ttm:agent` whole, while
   * Android's Expat-backed parser files it under its local name.
   */
  private fun Element.qualified(name: String): String {
    getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
    val local = name.substringAfter(':')
    val found = attributes ?: return ""
    for (i in 0 until found.length) {
      val attribute = found.item(i) ?: continue
      if (attribute.nodeName == name || attribute.nodeName == local || attribute.localName == local) {
        return attribute.nodeValue.orEmpty()
      }
    }
    return ""
  }

  private fun lineFrom(paragraph: Element): TimedLine? {
    val pieces = mutableListOf<Piece>()
    collect(paragraph, pieces)
    val words = mergeIntoWords(pieces)

    if (words.isEmpty()) {
      // Line-synced TTML: a <p> with a stamp and bare text, no spans.
      val text = paragraph.textContent?.trim().orEmpty()
      val begin = time(paragraph.getAttribute("begin")) ?: return null
      if (text.isEmpty()) return null
      val end = time(paragraph.getAttribute("end"))?.takeIf { it > begin } ?: begin
      return TimedLine(SyncedLine(time = begin, line = text), end)
    }

    // Prefer the paragraph's own stamp: Apple sets it a hair before the first
    // syllable on lines that open with a soft consonant, and that lead-in is
    // when the line should appear.
    val begin = time(paragraph.getAttribute("begin")) ?: words.first().time
    val end =
      time(paragraph.getAttribute("end"))
        ?: (words.lastOrNull()?.let { it.time + 1 } ?: begin)
    return TimedLine(
      line =
        SyncedLine(
          time = minOf(begin, words.first().time),
          line = words.joinToString(" ") { it.word },
          words = words,
        ),
      endMs = maxOf(end, begin),
    )
  }

  /**
   * Flattens a paragraph into timed spans and the whitespace between them.
   * Nested spans (Apple wraps background vocals, and occasionally whole
   * phrases, in an outer timed span) recurse to their leaves, so only the
   * innermost timings — the ones actually per-syllable — survive.
   */
  private fun collect(
    node: Node,
    out: MutableList<Piece>,
  ) {
    val children = node.childNodes
    for (i in 0 until children.length) {
      when (val child = children.item(i)) {
        is Element -> {
          if (child.qualified("ttm:role") in SKIPPED_ROLES) continue
          val begin = time(child.getAttribute("begin"))
          val end = time(child.getAttribute("end"))
          if (begin != null && end != null && !hasTimedChild(child)) {
            out += Piece.Timed(child.textContent.orEmpty(), begin, end)
          } else {
            collect(child, out)
          }
        }
        else ->
          if (child.nodeType == Node.TEXT_NODE) {
            val text = child.textContent.orEmpty()
            if (text.isNotEmpty()) out += Piece.Text(text)
          }
      }
    }
  }

  private fun hasTimedChild(element: Element): Boolean {
    val children = element.childNodes
    for (i in 0 until children.length) {
      val child = children.item(i) as? Element ?: continue
      if (child.getAttribute("begin").isNotEmpty() || hasTimedChild(child)) return true
    }
    return false
  }

  /**
   * Glues syllables back into words. A word ends at the first whitespace after
   * it — whether that whitespace is a text node between two spans or part of a
   * span's own text — and runs from its first syllable's start to its last one's
   * end. Each word takes the start of the first syllable that fed it; the end
   * is recovered from the following word when the panel draws it.
   */
  private fun mergeIntoWords(pieces: List<Piece>): List<SyncedWord> {
    val words = mutableListOf<SyncedWord>()
    val current = StringBuilder()
    var start = 0
    // Untimed text is punctuation hanging off a span, or a line that was never
    // word-timed at all. Either way it cannot carry a word of its own.
    var timed = false

    fun flush() {
      val text = current.toString().trim()
      current.setLength(0)
      if (text.isNotEmpty() && timed) words += SyncedWord(time = start, word = text, startsNewWord = true)
      timed = false
    }

    pieces.forEach { piece ->
      when (piece) {
        is Piece.Text ->
          when {
            piece.text.isBlank() -> flush()
            timed -> current.append(piece.text)
            else -> Unit
          }
        is Piece.Timed -> {
          if (piece.text.isBlank()) return@forEach
          if (piece.text.first().isWhitespace()) flush()
          if (current.isEmpty()) start = piece.start
          current.append(piece.text.trim())
          timed = true
          if (piece.text.last().isWhitespace()) flush()
        }
      }
    }
    flush()
    return words
  }

  /**
   * TTML clock values: `27.395`, `1:05.20`, `1:02:03.4`, or a plain number with
   * an `s`/`ms` unit. Returned in milliseconds.
   */
  internal fun time(value: String?): Int? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toInt()
    val stripped = raw.removeSuffix("s")
    val parts = stripped.split(':')
    val seconds =
      when (parts.size) {
        1 -> parts[0].toDoubleOrNull()
        2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
        3 ->
          parts[0].toDoubleOrNull()?.let { h ->
            parts[1].toDoubleOrNull()?.let { m ->
              parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it }
            }
          }
        else -> null
      } ?: return null
    return (seconds * 1000).toInt()
  }

  private sealed interface Piece {
    data class Text(
      val text: String,
    ) : Piece

    data class Timed(
      val text: String,
      val start: Int,
      val end: Int,
    ) : Piece
  }
}
