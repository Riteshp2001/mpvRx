/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubtitleTranslationEngineTest {
  private fun engine(cache: TranslationBatchCache = MemoryBatchCache()) = SubtitleTranslationEngine(cache, retryDelaysMs = listOf(1, 1, 1))

  private fun timingLines(text: String) = text.lines().filter { "-->" in it || it.matches(Regex("\\d+")) }

  @Test
  fun srtKeepsNumbersTimingAndTags() =
    runBlocking {
      val result = engine().translate(SAMPLE_SRT, SubtitleFormat.SRT, FakeTranslator(), "en", "fr", rtlFix = false)
      val out = result.content
      assertEquals(timingLines(SAMPLE_SRT), timingLines(out))
      assertTrue(out.contains("<i>«Where are you going?»</i>"))
      // Edge tag peeled off, two short lines joined into one translated line.
      assertTrue(out, out.contains("{\\an8}«I told you, I'm leaving tonight.»"))
      // Dialogue lines are translated one by one and keep their dashes.
      assertTrue(out.contains("- «Wait!»\n- «No.»"))
      // Music-only cue is never sent.
      assertTrue(out.contains("\n♪ ♪\n"))
      // Inner tags survive as tokens.
      assertTrue(out, out.contains("«He said <b>no</b> again.»"))
      assertEquals(0, result.failedUnits)
    }

  @Test
  fun assKeepsHeaderStylesTimingAndOverrides() =
    runBlocking {
      val result = engine().translate(SAMPLE_ASS, SubtitleFormat.ASS, FakeTranslator(), "en", "fr", rtlFix = true)
      val out = result.content
      val keep = SAMPLE_ASS.lines().filterNot { it.startsWith("Dialogue: 0") }
      assertTrue(keep.all { it in out.lines() })
      assertTrue(out, out.contains("Default,,0,0,0,,{\\pos(960,900)}«Hello, my friend. How are you?»"))
      assertTrue(out, out.contains("Default,Bob,0,0,0,,«I said {\\i1}never{\\i0}, okay?»"))
      assertTrue(out, out.contains("Default,,0,0,0,,{\\an8\\fad(200,200)}«Commas, inside, text.»"))
      assertTrue(out.contains("{\\p1}m 0 0 l 100 0 100 100 0 100{\\p0}"))
    }

  @Test
  fun longTranslationsAreRewrappedToTheOriginalLineCount() =
    runBlocking {
      val srt = "1\n00:00:01,000 --> 00:00:02,000\nThis is a fairly long subtitle line\nthat continues on the second line\n"
      val out = engine().translate(srt, SubtitleFormat.SRT, FakeTranslator(), "en", "fr", rtlFix = false).content
      val text = out.lines().drop(2).filter(String::isNotEmpty)
      assertEquals(2, text.size)
      assertEquals("«This is a fairly long subtitle line that continues on the second line»", text.joinToString(" "))
    }

  @Test
  fun misalignedBatchesAreSplitAndRetried() =
    runBlocking {
      // Drops the last text whenever more than one is sent — like an LLM merging two lines.
      val translator =
        object : FakeTranslator(maxTextsPerRequest = 4) {
          override suspend fun translate(
            texts: List<String>,
            sourceLanguage: String?,
            targetLanguage: String,
          ): List<String> {
            val answer = super.translate(texts, sourceLanguage, targetLanguage)
            return if (texts.size > 1) answer.dropLast(1) else answer
          }
        }
      val out = engine().translate(SAMPLE_SRT, SubtitleFormat.SRT, translator, "en", "fr", rtlFix = false).content
      assertTrue(out.contains("<i>«Where are you going?»</i>"))
      assertTrue(out.contains("- «Wait!»\n- «No.»"))
      assertTrue(out.contains("«He said <b>no</b> again.»"))
      assertTrue(translator.requests.any { it.size == 1 })
    }

  @Test
  fun droppedTokensAreReattachedInsteadOfLost() =
    runBlocking {
      val translator = FakeTranslator(transform = { "X" + it.replace(Regex("⟦\\d+⟧"), "") })
      val out = engine().translate(SAMPLE_ASS, SubtitleFormat.ASS, translator, "en", "fr", rtlFix = false).content
      val line = out.lines().first { it.contains(",Bob,") }
      assertTrue(line, line.contains("{\\i1}"))
      assertTrue(line, line.endsWith("{\\i0}"))
    }

  @Test
  fun enginesWithoutPlaceholderSupportGetPlainSentences() =
    runBlocking {
      val translator =
        object : FakeTranslator(maxTextsPerRequest = 50) {
          override val preservesInlineMarkup = false
        }
      val out = engine().translate(SAMPLE_ASS, SubtitleFormat.ASS, translator, "en", "fr", rtlFix = false).content
      val sent = translator.requests.flatten()
      assertTrue(sent.none { "⟦" in it })
      assertTrue(sent.toString(), "I said never, okay?" in sent)
      // The word is translated; its italics now cover the whole line.
      assertTrue(out, out.contains(",Bob,0,0,0,,{\\i1}«I said never, okay?»{\\i0}"))
    }

  @Test
  fun perLineItalicsAreNotDoubledWhenJoined() =
    runBlocking {
      val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i>KATE: I was in seventh grade</i>\n<i>when I realized I was invisible.</i>\n"
      val translator =
        object : FakeTranslator(maxTextsPerRequest = 50) {
          override val preservesInlineMarkup = false
        }
      val out = engine().translate(srt, SubtitleFormat.SRT, translator, "en", "fr", rtlFix = false).content
      val text = out.lines().drop(2).filter(String::isNotEmpty).joinToString("\n")
      assertEquals(1, Regex("<i>").findAll(text).count())
      assertEquals(1, Regex("</i>").findAll(text).count())
      assertTrue(text, text.startsWith("<i>«KATE") && text.endsWith("»</i>"))

      // Real SDH line: the speaker label sits outside the italics.
      val sdh = "1\n00:00:33,200 --> 00:00:36,703\nKATE: <i>I was in seventh grade</i>\n<i>when I realized I was invisible.</i>\n"
      val sdhText = engine().translate(sdh, SubtitleFormat.SRT, translator, "en", "ar", rtlFix = false).content.lines().drop(2).joinToString("\n")
      assertEquals(sdhText, 1, Regex("<i>").findAll(sdhText).count())
      assertEquals(sdhText, 1, Regex("</i>").findAll(sdhText).count())
    }

  @Test
  fun resumesFromCacheAfterFailure() =
    runBlocking {
      val cache = MemoryBatchCache()
      var calls = 0
      val failing =
        object : FakeTranslator(maxTextsPerRequest = 1) {
          override suspend fun translate(
            texts: List<String>,
            sourceLanguage: String?,
            targetLanguage: String,
          ): List<String> {
            if (++calls == 3) throw SubtitleTranslationException.QuotaExceeded("quota")
            return super.translate(texts, sourceLanguage, targetLanguage)
          }
        }
      try {
        engine(cache).translate(SAMPLE_SRT, SubtitleFormat.SRT, failing, "en", "fr", rtlFix = false)
        fail("expected quota error")
      } catch (_: SubtitleTranslationException.QuotaExceeded) {
      }
      assertEquals(1, cache.store.size)
      assertEquals(2, cache.store.values.single().size)

      val second = FakeTranslator(maxTextsPerRequest = 1)
      var resumed = false
      engine(cache).translate(SAMPLE_SRT, SubtitleFormat.SRT, second, "en", "fr", rtlFix = false) { if (it.resumed) resumed = true }
      assertTrue(resumed)
      // 5 translatable units (dialogue cue = 2, music cue = 0), 2 were cached.
      assertEquals(3, second.requests.size)
      assertTrue(cache.store.isEmpty())
    }

  @Test
  fun transientErrorsAreRetried() =
    runBlocking {
      var calls = 0
      val flaky =
        object : FakeTranslator(maxTextsPerRequest = 50) {
          override suspend fun translate(
            texts: List<String>,
            sourceLanguage: String?,
            targetLanguage: String,
          ): List<String> {
            if (++calls < 3) throw SubtitleTranslationException.Server("503")
            return super.translate(texts, sourceLanguage, targetLanguage)
          }
        }
      val result = engine().translate(SAMPLE_SRT, SubtitleFormat.SRT, flaky, "en", "fr", rtlFix = false)
      assertEquals(3, calls)
      assertEquals(0, result.failedUnits)
    }

  @Test
  fun arabicTargetGetsRtlEmbeddingOutsideTags() =
    runBlocking {
      val out = engine().translate(SAMPLE_SRT, SubtitleFormat.SRT, FakeTranslator(transform = { "مرحبا!" }), "en", "ar", rtlFix = true).content
      assertTrue(out, out.contains("<i>‫مرحبا!‬</i>"))
      assertTrue(out, out.contains("{\\an8}‫مرحبا!‬"))
      assertTrue(out, out.contains("‫- مرحبا!‬"))
      assertFalse(out.lines().any { "-->" in it && '‫' in it })
    }

  @Test
  fun batchesRespectCountAndCharacterLimits() {
    val ranges = engine().batch(listOf("aaaa", "bbbb", "cccc", "dddd", "e"), maxTexts = 3, maxChars = 11)
    assertEquals(listOf(0..1, 2..3, 4..4), ranges)
  }
}
