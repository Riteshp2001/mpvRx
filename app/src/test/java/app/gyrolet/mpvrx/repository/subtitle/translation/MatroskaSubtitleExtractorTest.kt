/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class MatroskaSubtitleExtractorTest {
  // --- minimal EBML writer -------------------------------------------------------------
  private fun id(value: Long): ByteArray {
    val bytes = mutableListOf<Byte>()
    var v = value
    while (v > 0) {
      bytes.add(0, (v and 0xFF).toByte())
      v = v shr 8
    }
    return bytes.toByteArray()
  }

  private fun size(value: Long): ByteArray {
    // 8-byte size: 0x01 marker followed by 7 bytes.
    val out = ByteArray(8)
    out[0] = 0x01
    for (i in 1..7) out[i] = ((value shr (8 * (7 - i))) and 0xFF).toByte()
    return out
  }

  private fun el(
    idValue: Long,
    vararg children: ByteArray,
  ): ByteArray {
    val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
    return id(idValue) + size(body.size.toLong()) + body
  }

  private fun uint(
    idValue: Long,
    value: Long,
  ) = el(idValue, byteArrayOf(((value shr 24) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte()))

  private fun str(
    idValue: Long,
    value: String,
  ) = el(idValue, value.toByteArray())

  private fun block(
    track: Int,
    relativeMs: Int,
    payload: ByteArray,
  ): ByteArray = byteArrayOf((0x80 or track).toByte(), (relativeMs shr 8).toByte(), (relativeMs and 0xFF).toByte(), 0) + payload

  private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater()
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(1024)
    while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    return out.toByteArray()
  }

  private val assHeader =
    "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize\nStyle: Default,Arial,50\n\n[Events]\n" +
      "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

  private fun sampleMkv(): ByteArray {
    val tracks =
      el(
        0x1654AE6B,
        el(0xAE, uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MPEGH/ISO/HEVC")),
        el(0xAE, uint(0xD7, 2), uint(0x83, 17), str(0x86, "S_TEXT/UTF8"), str(0x22B59C, "eng"), str(0x536E, "SDH")),
        el(
          0xAE,
          uint(0xD7, 3),
          uint(0x83, 17),
          str(0x86, "S_TEXT/ASS"),
          str(0x22B59C, "fre"),
          el(0x63A2, assHeader.toByteArray()),
          el(0x6D80, el(0x6240, el(0x5034, uint(0x4254, 0)))),
        ),
        el(0xAE, uint(0xD7, 4), uint(0x83, 17), str(0x86, "S_HDMV/PGS"), str(0x22B59C, "eng")),
      )
    val video = ByteArray(5000) { 7 }
    val cluster1 =
      el(
        0x1F43B675,
        uint(0xE7, 1000),
        el(0xA3, block(1, 0, video)),
        el(0xA0, el(0xA1, block(2, 500, "Hello <i>there</i>".toByteArray())), uint(0x9B, 2000)),
        el(0xA3, block(1, 40, video)),
        el(0xA0, el(0xA1, block(3, 600, deflate("1,0,Default,,0,0,0,,{\\an8}Bonjour, ami".toByteArray()))), uint(0x9B, 1500)),
        el(0xA0, el(0xA1, block(4, 700, ByteArray(300) { 1 })), uint(0x9B, 1000)),
      )
    val cluster2 =
      el(
        0x1F43B675,
        uint(0xE7, 10_000),
        el(0xA3, block(1, 0, video)),
        el(0xA0, el(0xA1, block(2, 250, "Second\nline two".toByteArray())), uint(0x9B, 1250)),
        el(0xA0, el(0xA1, block(3, 0, deflate("0,0,Default,,0,0,0,,Premier".toByteArray()))), uint(0x9B, 900)),
      )
    val segment = el(0x18538067, el(0x1549A966, uint(0x2AD7B1, 1_000_000)), tracks, cluster1, cluster2)
    return el(0x1A45DFA3, uint(0x4282, 0)) + segment
  }

  @Test
  fun listsSubtitleTracksInFileOrder() {
    val tracks = MatroskaSubtitleExtractor.listSubtitleTracks(ByteArraySource(sampleMkv()))
    assertEquals(listOf(2L, 3L, 4L), tracks.map { it.number })
    assertEquals(listOf("eng", "fre", "eng"), tracks.map { it.language })
    assertEquals(listOf(true, true, false), tracks.map { it.isText })
  }

  @Test
  fun extractsSrtTrackWithTimings() =
    runBlocking {
      val result = MatroskaSubtitleExtractor.extract(ByteArraySource(sampleMkv()), { it.first { t -> t.number == 2L } })
      assertEquals(SubtitleFormat.SRT, result.format)
      assertEquals(
        "1\n00:00:01,500 --> 00:00:03,500\nHello <i>there</i>\n\n2\n00:00:10,250 --> 00:00:11,500\nSecond\nline two\n\n",
        result.content,
      )
    }

  @Test
  fun extractsCompressedAssTrackInReadOrder() =
    runBlocking {
      val result = MatroskaSubtitleExtractor.extract(ByteArraySource(sampleMkv()), { it.first { t -> t.number == 3L } })
      assertEquals(SubtitleFormat.ASS, result.format)
      assertTrue(result.content.startsWith(assHeader))
      val events = result.content.lines().filter { it.startsWith("Dialogue:") }
      assertEquals(
        listOf(
          "Dialogue: 0,0:00:10.00,0:00:10.90,Default,,0,0,0,,Premier",
          "Dialogue: 0,0:00:01.60,0:00:03.10,Default,,0,0,0,,{\\an8}Bonjour, ami",
        ),
        events,
      )
      // The extracted file parses and round-trips like any ASS file.
      assertEquals(result.content, SubtitleDocument.parse(result.content, SubtitleFormat.ASS).render())
    }

  @Test
  fun rejectsImageSubtitles() =
    runBlocking {
      try {
        MatroskaSubtitleExtractor.extract(ByteArraySource(sampleMkv()), { it.first { t -> t.number == 4L } })
        fail("PGS must be rejected")
      } catch (e: SubtitleTranslationException.UnsupportedLanguage) {
        assertTrue(e.message!!.contains("image-based"))
      }
    }
}
