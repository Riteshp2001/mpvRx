/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Shared sweep from FloatingLyricsPill, based on Mochy Music (GPL-3.0):
 * https://github.com/realidkroo/Mochy-Music/tree/bde01cb4321895c406ca93679462447236c2b2c3
 * See app/src/main/kotlin/com/mochyy/mochymusic/floating in that revision.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText

internal data class LyricWordSpan(val from: Int, val to: Int, val start: Long, val end: Long)

internal fun lyricWordEndTime(
  words: List<SyncedWord>,
  index: Int,
  lineEndTimeMs: Long?,
  nextLineTimeMs: Long? = null,
): Long {
  val start = words[index].time.toLong()
  return words.getOrNull(index + 1)?.time?.toLong()?.takeIf { it > start }
    ?: lineEndTimeMs?.takeIf { it > start }
    ?: nextLineTimeMs?.takeIf { it > start }
    ?: (start + 600L)
}

internal fun lyricWordSpans(text: String, words: List<SyncedWord>, endTimeMs: Long): List<LyricWordSpan> =
  buildList {
    var cursor = 0
    for ((index, word) in words.withIndex()) {
      if (word.word.isEmpty()) continue
      val start = text.indexOf(word.word, cursor)
      if (start < 0) continue
      val end = start + word.word.length
      val endTime = lyricWordEndTime(words, index, endTimeMs)
      add(LyricWordSpan(start, end, word.time.toLong(), endTime))
      cursor = end
    }
  }

internal fun lyricCharacterPosition(textLength: Int, spans: List<LyricWordSpan>, position: Long): Float {
  for (span in spans) {
    if (position < span.start) return span.from.toFloat()
    if (position < span.end) {
      val progress = ((position - span.start).toFloat() / (span.end - span.start).coerceAtLeast(1)).coerceIn(0f, 1f)
      return span.from + (span.to - span.from) * progress
    }
  }
  return if (spans.isEmpty()) 0f else textLength.toFloat()
}

internal fun lyricSweepX(layout: TextLayoutResult, head: Float, rtl: Boolean): Float {
  val length = layout.layoutInput.text.length
  if (length == 0) return 0f
  if (head >= length) return if (rtl) 0f else layout.size.width.toFloat()
  val index = head.toInt().coerceIn(0, length - 1)
  val box = layout.getBoundingBox(index)
  val fraction = (head - index).coerceIn(0f, 1f)
  return if (rtl) box.right - box.width * fraction else box.left + box.width * fraction
}

/** The floating pill's soft glyph sweep, clipped per visual line when fullscreen text wraps. */
internal fun DrawScope.drawLyricFill(
  layout: TextLayoutResult,
  head: Float,
  rtl: Boolean,
  activeColor: Color,
  inactiveColor: Color,
) {
  when {
    head <= 0f -> drawText(layout, color = inactiveColor)
    head >= layout.layoutInput.text.length -> drawText(layout, color = activeColor)
    else -> {
      val x = lyricSweepX(layout, head, rtl)
      val direction = if (rtl) -1f else 1f
      val brush = Brush.horizontalGradient(
        0f to activeColor, 1f to inactiveColor,
        startX = x - layout.layoutInput.style.fontSize.toPx() * 0.55f * direction,
        endX = x + 2.dp.toPx() * direction,
      )
      if (layout.lineCount == 1) {
        drawText(layout, brush = brush, alpha = 1f)
      } else {
        val line = layout.getLineForOffset(head.toInt())
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val right = layout.size.width.toFloat()
        clipRect(right = right, bottom = top) { drawText(layout, color = activeColor) }
        clipRect(right = right, top = top, bottom = bottom) { drawText(layout, brush = brush, alpha = 1f) }
        clipRect(right = right, top = bottom, bottom = layout.size.height.toFloat()) {
          drawText(layout, color = inactiveColor)
        }
      }
    }
  }
}

@Composable
internal fun SweepingLyricText(
  text: String,
  words: List<SyncedWord>,
  endTimeMs: Long,
  positionMs: State<Long>,
  activeColor: Color,
  inactiveColor: Color,
  fontSize: TextUnit,
  textAlign: TextAlign,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
  maxLines: Int = Int.MAX_VALUE,
  softWrap: Boolean = true,
  onTextLayout: (TextLayoutResult) -> Unit = {},
) {
  val spans = remember(text, words, endTimeMs) { lyricWordSpans(text, words, endTimeMs) }
  var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
  val reducedMotion = AppMotion.playerReducedMotion()
  val rtl = remember(text) { text.hasRtlDirection() }
  Text(
    text = text,
    color = activeColor,
    style = style.copy(
      fontSize = fontSize,
      fontFamily = fontFamilyForText(text),
      textAlign = textAlign,
    ),
    maxLines = maxLines,
    softWrap = softWrap,
    onTextLayout = {
      layout = it
      onTextLayout(it)
    },
    modifier = modifier.drawWithContent {
      val measured = layout
      if (measured == null || spans.isEmpty() || reducedMotion) {
        drawContent()
      } else {
        drawLyricFill(
          measured, lyricCharacterPosition(text.length, spans, positionMs.value), rtl, activeColor, inactiveColor,
        )
      }
    },
  )
}
