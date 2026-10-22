package app.gyrolet.mpvrx.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import app.gyrolet.mpvrx.core.player.Chapter
import app.gyrolet.mpvrx.core.preferences.SeekbarStyle
import app.gyrolet.mpvrx.core.util.formatDuration
import kotlin.math.roundToInt

/**
 * Thick expressive seekbar from the Android player: rounded track, buffered range, chapter
 * gaps and a pill thumb. Adds hover time preview for mouse users.
 */
@Composable
fun PlayerSeekbar(
  position: Double,
  duration: Double,
  buffered: Double,
  chapters: List<Chapter>,
  style: SeekbarStyle,
  showChapters: Boolean,
  showBuffered: Boolean,
  onSeek: (Double) -> Unit,
  onSeekPreview: (Double?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val primary = MaterialTheme.colorScheme.primary
  val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
  val bufferedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
  var widthPx by remember { mutableFloatStateOf(1f) }
  var dragFraction by remember { mutableStateOf<Float?>(null) }
  var hoverFraction by remember { mutableStateOf<Float?>(null) }
  val trackHeight =
    when (style) {
      SeekbarStyle.Thick -> 12.dp
      SeekbarStyle.Standard -> 6.dp
      SeekbarStyle.Slim -> 4.dp
    }
  val baseFraction = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
  val shownFraction = dragFraction ?: baseFraction
  val animatedThumb by animateFloatAsState(if (hoverFraction != null || dragFraction != null) 1f else 0.85f, label = "thumb")
  val density = LocalDensity.current

  Box(modifier.fillMaxWidth().height(36.dp)) {
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(36.dp)
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(duration) {
          detectTapGestures { offset ->
            if (duration > 0) onSeek(duration * (offset.x / widthPx).coerceIn(0f, 1f))
          }
        }.pointerInput(duration) {
          detectHorizontalDragGestures(
            onDragStart = { offset ->
              dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
              onSeekPreview(duration * dragFraction!!)
            },
            onDragEnd = {
              dragFraction?.let { onSeek(duration * it) }
              dragFraction = null
              onSeekPreview(null)
            },
            onDragCancel = {
              dragFraction = null
              onSeekPreview(null)
            },
          ) { change, _ ->
            dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
            onSeekPreview(duration * dragFraction!!)
          }
        }.pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              when (event.type) {
                PointerEventType.Move, PointerEventType.Enter ->
                  hoverFraction = (event.changes.first().position.x / widthPx).coerceIn(0f, 1f)
                PointerEventType.Exit -> hoverFraction = null
              }
            }
          }
        },
    ) {
      val h = trackHeight.toPx()
      val top = (size.height - h) / 2
      val radius = CornerRadius(h / 2, h / 2)
      val gap = 3.dp.toPx()
      val thumbX = size.width * shownFraction
      // Remaining track
      drawRoundRect(track, Offset(0f, top), Size(size.width, h), radius)
      if (showBuffered && duration > 0 && buffered > position) {
        val end = (buffered / duration).toFloat().coerceIn(0f, 1f) * size.width
        drawRoundRect(bufferedColor, Offset(thumbX, top), Size((end - thumbX).coerceAtLeast(0f), h), radius)
      }
      // Played track stops short of the thumb, like Material 3 expressive sliders.
      val playedEnd = (thumbX - gap * 2).coerceAtLeast(0f)
      if (playedEnd > 0f) drawRoundRect(primary, Offset(0f, top), Size(playedEnd, h), radius)
      if (showChapters && duration > 0) {
        chapters.drop(1).forEach { chapter ->
          val x = (chapter.startSeconds / duration).toFloat() * size.width
          drawRect(Color.Black.copy(alpha = 0.55f), Offset(x - 1.dp.toPx(), top), Size(2.dp.toPx(), h))
        }
      }
      val thumbW = 4.dp.toPx()
      val thumbH = (h + 14.dp.toPx()) * animatedThumb
      drawRoundRect(
        primary,
        Offset(thumbX - thumbW / 2, (size.height - thumbH) / 2),
        Size(thumbW, thumbH),
        CornerRadius(thumbW, thumbW),
      )
    }

    val previewFraction = dragFraction ?: hoverFraction
    if (previewFraction != null && duration > 0) {
      val seconds = duration * previewFraction
      val chapterTitle = chapters.lastOrNull { it.startSeconds <= seconds }?.title
      val label = listOfNotNull(formatDuration(seconds), chapterTitle?.takeIf(String::isNotBlank)).joinToString(" · ")
      val xPx = (widthPx * previewFraction).roundToInt()
      val labelOffset = with(density) { IntOffset(xPx - 40.dp.roundToPx(), (-34).dp.roundToPx()) }
      Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier =
          Modifier
            .align(Alignment.TopStart)
            .offset { labelOffset }
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
      )
    }
  }
}
