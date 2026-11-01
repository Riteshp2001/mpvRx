/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class SubtitleTextDecoderTest {
  private val arabic = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا بك في الفيلم، كيف حالك اليوم؟\nأنا بخير شكرا لك\n"
  private val russian = "1\n00:00:01,000 --> 00:00:02,000\nПривет, как дела? Я иду домой сегодня вечером.\n"

  @Test
  fun detectsWindows1256Arabic() {
    val decoded = SubtitleTextDecoder.decode(arabic.toByteArray(Charset.forName("windows-1256")))
    assertEquals("windows-1256", decoded.charset)
    assertEquals(arabic, decoded.text)
  }

  @Test
  fun detectsWindows1251Russian() {
    val decoded = SubtitleTextDecoder.decode(russian.toByteArray(Charset.forName("windows-1251")))
    assertEquals("windows-1251", decoded.charset)
    assertEquals(russian, decoded.text)
  }

  @Test
  fun prefersUtf8AndStripsBom() {
    assertEquals(arabic, SubtitleTextDecoder.decode(arabic.toByteArray()).text)
    assertEquals(arabic, SubtitleTextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + arabic.toByteArray()).text)
  }

  @Test
  fun detectsUtf16WithAndWithoutBom() {
    assertEquals(russian, SubtitleTextDecoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + russian.toByteArray(Charsets.UTF_16LE)).text)
    assertEquals(russian, SubtitleTextDecoder.decode(russian.toByteArray(Charsets.UTF_16LE)).text)
  }

  @Test
  fun normalizesLanguageTags() {
    assertEquals("en", TranslationLanguages.normalizeCode("eng"))
    assertEquals("ar", TranslationLanguages.normalizeCode("ara"))
    assertEquals("fr", TranslationLanguages.normalizeCode("fre"))
    assertEquals("de", TranslationLanguages.normalizeCode("ger"))
    assertEquals("pt", TranslationLanguages.normalizeCode("pt-BR"))
    assertEquals("zh-TW", TranslationLanguages.normalizeCode("zh-Hant"))
    assertEquals("he", TranslationLanguages.normalizeCode("iw"))
    assertNull(TranslationLanguages.normalizeCode("und"))
    assertEquals("ar", TranslationLanguages.fromNameOrCode("Arabic")?.code)
    assertEquals(true, TranslationLanguages.fromNameOrCode("ar")?.rtl)
  }
}
