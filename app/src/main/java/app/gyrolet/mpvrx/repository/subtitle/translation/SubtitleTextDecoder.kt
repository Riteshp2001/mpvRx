/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes subtitle bytes without assuming UTF-8. Older subtitle files are often stored in a
 * Windows code page (Arabic subtitles in windows-1256 are very common), which decodes to garbage
 * when read as UTF-8.
 */
object SubtitleTextDecoder {
  data class Decoded(
    val text: String,
    val charset: String,
  )

  private val legacyCandidates =
    listOf("windows-1252", "windows-1250", "windows-1251", "windows-1253", "windows-1254", "windows-1255", "windows-1256")

  private val languageCharsets =
    mapOf(
      "ar" to "windows-1256", "fa" to "windows-1256", "ur" to "windows-1256",
      "ru" to "windows-1251", "uk" to "windows-1251", "bg" to "windows-1251", "sr" to "windows-1251",
      "el" to "windows-1253",
      "he" to "windows-1255",
      "tr" to "windows-1254",
      "pl" to "windows-1250", "cs" to "windows-1250", "sk" to "windows-1250", "hu" to "windows-1250",
      "ro" to "windows-1250", "hr" to "windows-1250", "sl" to "windows-1250",
    )

  /** @param languageHint ISO 639-1 language the file is probably in (from track metadata or file name). */
  fun decode(
    bytes: ByteArray,
    languageHint: String? = null,
  ): Decoded {
    when {
      bytes.startsWith(0xEF, 0xBB, 0xBF) -> return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
      bytes.startsWith(0xFF, 0xFE) -> return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
      bytes.startsWith(0xFE, 0xFF) -> return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
    }
    utf16WithoutBom(bytes)?.let { charset -> return Decoded(String(bytes, charset), charset.name()) }
    strictUtf8(bytes)?.let { return Decoded(it.removePrefix("﻿"), "UTF-8") }

    val hinted = languageHint?.let { languageCharsets[it.substringBefore('-')] }
    val best =
      legacyCandidates
        .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
        .maxBy { charset ->
          val score = plausibility(String(bytes, charset))
          if (charset.name().equals(hinted, ignoreCase = true)) score + bonusFor(bytes) else score
        }
    return Decoded(String(bytes, best), best.name())
  }

  private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

  private fun strictUtf8(bytes: ByteArray): String? =
    try {
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (_: CharacterCodingException) {
      null
    }

  private fun utf16WithoutBom(bytes: ByteArray): Charset? {
    val sample = minOf(bytes.size, 4096) and 1.inv()
    if (sample < 16) return null
    var evenZeros = 0
    var oddZeros = 0
    for (i in 0 until sample) {
      if (bytes[i].toInt() == 0) if (i % 2 == 0) evenZeros++ else oddZeros++
    }
    val half = sample / 2
    return when {
      oddZeros > half * 0.3 && evenZeros < half * 0.05 -> Charsets.UTF_16LE
      evenZeros > half * 0.3 && oddZeros < half * 0.05 -> Charsets.UTF_16BE
      else -> null
    }
  }

  /** A hint only breaks near-ties; it must not override a clearly better decoding. */
  private fun bonusFor(bytes: ByteArray): Int = maxOf(4, bytes.count { it.toInt() and 0x80 != 0 } / 20)

  /**
   * Scores how much a decoding looks like real text. A wrong single-byte code page produces words
   * that mix scripts (Russian read as windows-1256 → Arabic + accented Latin), flip letter case
   * mid-word (Arabic read as windows-1251 → Cyrillic with random capitals), or Latin words made
   * almost entirely of accented letters (Cyrillic read as windows-1252 → "Ïðèâåò").
   */
  internal fun plausibility(text: String): Int {
    var score = 0
    var wordScript: Character.UnicodeScript? = null
    var mixed = false
    var previousLower = false
    var asciiLetters = 0
    var accentedLatin = 0

    fun endWord() {
      if (mixed) score -= 6
      if (accentedLatin >= 2 && accentedLatin > asciiLetters) score -= 2 * accentedLatin
      wordScript = null
      mixed = false
      previousLower = false
      asciiLetters = 0
      accentedLatin = 0
    }

    for (ch in text) {
      if (!Character.isLetter(ch)) {
        if (ch.code in 0x80..0x9F || ch == '�') score -= 10
        endWord()
        continue
      }
      if (ch.code < 0x80) {
        if (wordScript != null && wordScript != Character.UnicodeScript.LATIN) mixed = true
        wordScript = wordScript ?: Character.UnicodeScript.LATIN
        if (previousLower && Character.isUpperCase(ch)) score -= 1
        previousLower = Character.isLowerCase(ch)
        asciiLetters++
        continue
      }
      val script = Character.UnicodeScript.of(ch.code)
      if (wordScript != null && wordScript != script) mixed = true
      wordScript = script
      if (script == Character.UnicodeScript.LATIN) accentedLatin++
      if (previousLower && Character.isUpperCase(ch)) score -= 3
      previousLower = Character.isLowerCase(ch)
      score += 1
    }
    endWord()
    return score
  }
}
