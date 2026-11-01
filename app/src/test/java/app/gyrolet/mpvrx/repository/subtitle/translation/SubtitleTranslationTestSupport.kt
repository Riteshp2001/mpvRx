/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

internal class MemoryBatchCache : TranslationBatchCache {
  val store = mutableMapOf<String, Map<Int, List<String>>>()
  var clears = 0

  override fun load(key: String): Map<Int, List<String>> = store[key].orEmpty()

  override fun save(
    key: String,
    batches: Map<Int, List<String>>,
  ) {
    store[key] = batches.toMap()
  }

  override fun clear(key: String) {
    clears++
    store.remove(key)
  }
}

/** "Translates" by wrapping text in brackets, which keeps `⟦n⟧` tokens intact. */
internal open class FakeTranslator(
  override val maxTextsPerRequest: Int = 3,
  override val maxCharsPerRequest: Int = 10_000,
  private val transform: (String) -> String = { "«$it»" },
) : SubtitleTranslator {
  override val cacheId: String = "fake"
  val requests = mutableListOf<List<String>>()

  override suspend fun translate(
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String> {
    requests += texts
    return texts.map(transform)
  }
}

internal val SAMPLE_SRT =
  """
  1
  00:00:01,000 --> 00:00:03,500
  <i>Where are you going?</i>

  2
  00:00:04,000 --> 00:00:06,000
  {\an8}I told you,
  I'm leaving tonight.

  3
  00:00:06,500 --> 00:00:08,000
  - Wait!
  - No.

  4
  00:00:09,000 --> 00:00:10,000
  ♪ ♪

  5
  00:00:11,000 --> 00:00:13,000
  He said <b>no</b> again.

  """.trimIndent()

internal val SAMPLE_ASS =
  """
  [Script Info]
  Title: Test
  ScriptType: v4.00+
  PlayResX: 1920
  PlayResY: 1080

  [V4+ Styles]
  Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
  Style: Default,Arial,60,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,1,2,40,40,40,1
  Style: Sign,Arial,50,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,1,0,0,0,100,100,0,0,1,2,0,8,40,40,40,1

  [Events]
  Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
  Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\pos(960,900)}Hello, my friend.\NHow are you?
  Dialogue: 0,0:00:03.50,0:00:05.00,Default,Bob,0,0,0,,I said {\i1}never{\i0}, okay?
  Comment: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,This is a comment
  Dialogue: 1,0:00:06.00,0:00:08.00,Sign,,0,0,0,,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}
  Dialogue: 0,0:00:08.00,0:00:09.00,Default,,0,0,0,,{\an8\fad(200,200)}Commas, inside, text.
  """.trimIndent() + "\n"
