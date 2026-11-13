/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.fonts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads which Unicode code points a TrueType or OpenType font maps to a glyph. */
internal object FontCmap {
  private const val TAG_TTCF = 0x74746366
  private const val TAG_CMAP = 0x636D6170
  private const val MAX_CODE_POINT = 0x10FFFF

  /** Null when [bytes] is not a font with a Unicode character map. */
  fun codePoints(bytes: ByteArray): Set<Int>? =
    runCatching { read(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)) }
      .getOrNull()
      ?.takeIf { it.isNotEmpty() }

  private fun read(font: ByteBuffer): Set<Int> {
    // A font collection: its first font is enough.
    val sfnt = if (font.getInt(0) == TAG_TTCF) font.getInt(12) else 0
    val tableCount = u16(font, sfnt + 4)
    val cmap =
      (0 until tableCount)
        .map { sfnt + 12 + it * 16 }
        .firstOrNull { font.getInt(it) == TAG_CMAP }
        ?.let { font.getInt(it + 8) }
        ?: return emptySet()

    val codePoints = HashSet<Int>()
    for (index in 0 until u16(font, cmap + 2)) {
      val record = cmap + 4 + index * 8
      val platform = u16(font, record)
      val encoding = u16(font, record + 2)
      if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) continue
      val subtable = cmap + font.getInt(record + 4)
      when (u16(font, subtable)) {
        4 -> readSegments(font, subtable, codePoints)
        12 -> readGroups(font, subtable, codePoints)
      }
    }
    return codePoints
  }

  /** Format 4: the Basic Multilingual Plane, as segments with a delta or a glyph array. */
  private fun readSegments(
    font: ByteBuffer,
    table: Int,
    out: MutableSet<Int>,
  ) {
    val segments = u16(font, table + 6) / 2
    val ends = table + 14
    val starts = ends + segments * 2 + 2
    val deltas = starts + segments * 2
    val rangeOffsets = deltas + segments * 2
    for (segment in 0 until segments) {
      val start = u16(font, starts + segment * 2)
      val end = u16(font, ends + segment * 2)
      val delta = font.getShort(deltas + segment * 2).toInt()
      val rangeOffsetAt = rangeOffsets + segment * 2
      val rangeOffset = u16(font, rangeOffsetAt)
      for (codePoint in start..minOf(end, 0xFFFE)) {
        val glyph =
          if (rangeOffset == 0) {
            (codePoint + delta) and 0xFFFF
          } else {
            val raw = u16(font, rangeOffsetAt + rangeOffset + (codePoint - start) * 2)
            if (raw == 0) 0 else (raw + delta) and 0xFFFF
          }
        if (glyph != 0) out.add(codePoint)
      }
    }
  }

  /** Format 12: all planes, as ranges of consecutive glyphs. */
  private fun readGroups(
    font: ByteBuffer,
    table: Int,
    out: MutableSet<Int>,
  ) {
    val groups = font.getInt(table + 12)
    for (group in 0 until groups) {
      val record = table + 16 + group * 12
      val start = font.getInt(record)
      val end = minOf(font.getInt(record + 4), MAX_CODE_POINT)
      val firstGlyph = font.getInt(record + 8)
      if (start < 0 || end < start) continue
      for (codePoint in start..end) {
        if (firstGlyph + (codePoint - start) != 0) out.add(codePoint)
      }
    }
  }

  private fun u16(
    font: ByteBuffer,
    offset: Int,
  ): Int = font.getShort(offset).toInt() and 0xFFFF
}
