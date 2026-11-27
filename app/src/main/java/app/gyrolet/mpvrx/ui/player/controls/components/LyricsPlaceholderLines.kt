/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * One line out of a list of string resources, for the moments the lyrics panel has nothing
 * real to say: fetching, an instrumental intro, or no lyrics anywhere.
 *
 * The starting line is random so the panel doesn't open on the same sentence
 * every time, and it survives rotation. With [intervalMs] the line advances on
 * its own while on screen, which is what a fetch that takes a few seconds wants;
 * without it the line stays put, which is what an intro or a miss wants.
 */
@Composable
fun rememberPlaceholderLine(
  lines: List<Int>,
  intervalMs: Long? = null,
): String {
  var index by rememberSaveable(lines) {
    mutableIntStateOf(if (lines.isEmpty()) 0 else Random.nextInt(lines.size))
  }
  if (intervalMs != null && lines.size > 1) {
    LaunchedEffect(lines, intervalMs) {
      while (true) {
        delay(intervalMs)
        index = (index + 1) % lines.size
      }
    }
  }
  if (lines.isEmpty()) return ""
  return stringResource(lines[index % lines.size])
}

/** The lines the lyrics panel shows when it has nothing real to say. */
object LyricsPlaceholders {
  val LOADING: List<Int> =
    listOf(
      R.string.lyrics_loading_line_1,
      R.string.lyrics_loading_line_2,
      R.string.lyrics_loading_line_3,
      R.string.lyrics_loading_line_4,
      R.string.lyrics_loading_line_5,
      R.string.lyrics_loading_line_6,
      R.string.lyrics_loading_line_7,
      R.string.lyrics_loading_line_8,
      R.string.lyrics_loading_line_9,
      R.string.lyrics_loading_line_10,
    )
  val INTRO: List<Int> =
    listOf(
      R.string.lyrics_intro_line_1,
      R.string.lyrics_intro_line_2,
      R.string.lyrics_intro_line_3,
      R.string.lyrics_intro_line_4,
      R.string.lyrics_intro_line_5,
      R.string.lyrics_intro_line_6,
      R.string.lyrics_intro_line_7,
      R.string.lyrics_intro_line_8,
    )
  val EMPTY: List<Int> =
    listOf(
      R.string.lyrics_empty_line_1,
      R.string.lyrics_empty_line_2,
      R.string.lyrics_empty_line_3,
      R.string.lyrics_empty_line_4,
      R.string.lyrics_empty_line_5,
      R.string.lyrics_empty_line_6,
    )
}

/** How long each fetching line stays up before the next one replaces it. */
const val PLACEHOLDER_ROTATE_MS = 2_400L
