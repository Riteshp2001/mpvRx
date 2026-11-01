/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleDocumentTest {
  @Test
  fun srtRoundTripsExactly() {
    val document = SubtitleDocument.parse(SAMPLE_SRT, SubtitleFormat.SRT)
    assertEquals(SAMPLE_SRT, document.render())
    assertEquals(5, document.cues.size)
    assertEquals(listOf("{\\an8}I told you,", "I'm leaving tonight."), document.cues[1].lines)
  }

  @Test
  fun srtWithCrlfAndNoTrailingNewlineRoundTrips() {
    val crlf = SAMPLE_SRT.trimEnd().replace("\n", "\r\n")
    assertEquals(crlf, SubtitleDocument.parse(crlf, SubtitleFormat.SRT).render())
  }

  @Test
  fun vttKeepsHeaderNotesAndCueSettings() {
    val vtt =
      """
      WEBVTT
      Kind: captions

      NOTE this --> should stay

      STYLE
      ::cue { color: yellow }

      intro
      00:00:01.000 --> 00:00:02.000 align:start position:10%
      <v Anna>Hello there

      00:00:03.000 --> 00:00:04.000
      Bye
      """.trimIndent()
    val document = SubtitleDocument.parse(vtt, SubtitleFormat.VTT)
    assertEquals(vtt, document.render())
    assertEquals(listOf(listOf("<v Anna>Hello there"), listOf("Bye")), document.cues.map { it.lines })
  }

  @Test
  fun assOnlyExposesDialogueTextAndSkipsDrawingsAndComments() {
    val document = SubtitleDocument.parse(SAMPLE_ASS, SubtitleFormat.ASS)
    assertEquals(SAMPLE_ASS, document.render())
    assertEquals(
      listOf(
        listOf("{\\pos(960,900)}Hello, my friend.", "How are you?"),
        listOf("I said {\\i1}never{\\i0}, okay?"),
        listOf("{\\an8\\fad(200,200)}Commas, inside, text."),
      ),
      document.cues.map { it.lines },
    )
  }

  @Test
  fun switchesAssStyleFontsOnly() {
    val input = SAMPLE_ASS.replace("I said {\\i1}never", "I said {\\fnImpact\\i1}never")
    val out = SubtitleDocument.withAssFont(input, "sans-serif")
    assertEquals(
      listOf(
        "Style: Default,sans-serif,60,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,1,2,40,40,40,1",
        "Style: Sign,sans-serif,50,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,1,0,0,0,100,100,0,0,1,2,0,8,40,40,40,1",
      ),
      out.lines().filter { it.startsWith("Style:") },
    )
    assert(out.contains("{\\fnsans-serif\\i1}never"))
    // Nothing outside styles and font overrides changes.
    assertEquals(input.lines().size, out.lines().size)
    assertEquals(input.lines().filterNot { it.startsWith("Style:") || "\\fn" in it }, out.lines().filterNot { it.startsWith("Style:") || "\\fn" in it })
  }

  @Test
  fun sniffsFormats() {
    assertEquals(SubtitleFormat.SRT, SubtitleFormat.sniff(SAMPLE_SRT))
    assertEquals(SubtitleFormat.ASS, SubtitleFormat.sniff(SAMPLE_ASS))
    assertEquals(SubtitleFormat.VTT, SubtitleFormat.sniff("﻿WEBVTT\n\n"))
    assertNull(SubtitleFormat.sniff("just some text"))
  }
}
