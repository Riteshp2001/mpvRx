/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTitleMatcherTest {
  @Test
  fun `uses later keywords to break ties among earlier matches`() {
    val titles =
      listOf(
        "Official Simplified Chinese",
        "Effects Simplified Chinese",
        "Effects Traditional Chinese",
      )

    val result = SubtitleTitleMatcher.findBestMatchIndex(titles, listOf("effects", "simplified", "chs"))

    assertEquals(1, result)
  }

  @Test
  fun `earlier keyword cannot be outweighed by later keywords`() {
    val titles = listOf("Effects subtitles", "Simplified chs Chinese")

    val result = SubtitleTitleMatcher.findBestMatchIndex(titles, listOf("effects", "simplified", "chs"))

    assertEquals(0, result)
  }

  @Test
  fun `short language code requires an ascii token boundary`() {
    val titles = listOf("French", "Chinese [CH]")

    val result = SubtitleTitleMatcher.findBestMatchIndex(titles, listOf("ch"))

    assertEquals(1, result)
  }

  @Test
  fun `returns null when no title keyword matches`() {
    val result = SubtitleTitleMatcher.findBestMatchIndex(listOf("English", "日本語"), listOf("简", "繁"))

    assertNull(result)
  }
}
