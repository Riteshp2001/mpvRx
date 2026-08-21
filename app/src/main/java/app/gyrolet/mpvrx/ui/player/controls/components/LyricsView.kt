/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import app.gyrolet.mpvrx.ui.player.PlayerViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsView(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
  showTitleHeader: Boolean = false,
  isLyricsFullscreen: Boolean = false,
  onTap: (() -> Unit)? = null,
) {
  val state by viewModel.lyricsUiState.collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  var lyricsViewportPx by remember { mutableIntStateOf(0) }

  val currentPosMs = remember(precisePosition, state.syncOffsetMs) {
    (precisePosition * 1000).toLong() + state.syncOffsetMs
  }
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()
  // Position polls arrive every 50-500ms; per-letter animation needs a per-frame clock.
  val smoothPositionMs = rememberSmoothedPositionMs(currentPosMs, paused == false, playbackSpeed ?: 1f)

  // BetterLyrics-style focus: glide the active line to the vertical center of the viewport
  // instead of pinning it near the top.
  LaunchedEffect(state.activeLineIndex, isLyricsFullscreen, lyricsViewportPx) {
    val target = state.activeLineIndex
    if (target < 0) return@LaunchedEffect
    runCatching {
      val layout = listState.layoutInfo
      val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
      val item = layout.visibleItemsInfo.firstOrNull { it.index == target }
      if (item != null) {
        val itemCenter = item.offset + item.size / 2
        listState.animateScrollBy(
          (itemCenter - viewportCenter).toFloat(),
          animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        )
      } else {
        // Line is off-screen (seek/jump): land near mid-viewport, then fine-center it.
        val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
        listState.scrollToItem(target, scrollOffset = -viewportHeight / 2)
        val settled = listState.layoutInfo
        val settledCenter = (settled.viewportStartOffset + settled.viewportEndOffset) / 2
        settled.visibleItemsInfo.firstOrNull { it.index == target }?.let { visible ->
          val itemCenter = visible.offset + visible.size / 2
          listState.animateScrollBy(
            (itemCenter - settledCenter).toFloat(),
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
          )
        }
      }
    }
  }

  val hasEmbedded = state.embeddedLyrics != null && state.embeddedLyrics?.isValid() == true

  Surface(
    modifier = modifier
      .fillMaxSize()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onTap?.invoke() },
    color = Color.Transparent,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Optional Header
      if (showTitleHeader) {
        val mediaTitle by PlaybackSession.propString["media-title"].collectAsState()
        val artistName by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
        val displayTitle = mediaTitle?.takeIf { it.isNotBlank() } ?: "Current Track"
        val displayArtist = artistName?.takeIf { it.isNotBlank() } ?: ""

        Text(
          text = displayTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = FontFamily.SansSerif,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
        if (displayArtist.isNotBlank()) {
          Text(
            text = displayArtist,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
      }

      // Source Switcher Row (Show ONLY IF embedded/local lyrics are present)
      if (hasEmbedded) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilterChip(
            selected = state.selectedSource == LyricsSourceType.EMBEDDED || state.selectedSource == LyricsSourceType.LOCAL,
            onClick = { viewModel.switchLyricsSource(LyricsSourceType.EMBEDDED) },
            label = {
              Text(
                if (state.embeddedLyrics?.sourceType == LyricsSourceType.LOCAL) stringResource(R.string.lyrics_source_local) else stringResource(R.string.lyrics_source_embedded),
                fontWeight = FontWeight.Bold,
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )

          FilterChip(
            selected = state.selectedSource == LyricsSourceType.ONLINE,
            onClick = { viewModel.switchLyricsSource(LyricsSourceType.ONLINE) },
            label = {
              Text(stringResource(R.string.lyrics_source_online), fontWeight = FontWeight.Bold)
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )

          if (state.isLoading) {
            Spacer(modifier = Modifier.width(6.dp))
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        Spacer(modifier = Modifier.height(14.dp))
      }

      // Edge-to-Edge Synced Lyrics Scroll Area
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .onSizeChanged { lyricsViewportPx = it.height },
        contentAlignment = Alignment.Center,
      ) {
        val activeLyrics = state.lyrics
        when {
          state.isLoading && activeLyrics == null -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = "Fetching synced lyrics...",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          activeLyrics != null && !activeLyrics.synced.isNullOrEmpty() -> {
            // Half-viewport insets let the first and last lines reach the exact center.
            val centerInset =
              with(density) { (lyricsViewportPx / 2 - 48.dp.roundToPx()).coerceAtLeast(0).toDp() }
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(20.dp),
              contentPadding = PaddingValues(top = centerInset, bottom = centerInset),
            ) {
              itemsIndexed(
                items = activeLyrics.synced,
                key = { index, line -> "${line.time}_${index}" },
                contentType = { _, _ -> "lyric_synced_line" },
              ) { index, line ->
                val isActiveLine = index == state.activeLineIndex
                val (ogText, transText) = remember(line.line, line.translation) {
                  val rawTrans = line.translation?.trim()
                  if (!rawTrans.isNullOrBlank()) {
                    Pair(line.line.trim(), rawTrans)
                  } else if (line.line.contains("\n")) {
                    val parts = line.line.split("\n", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else if (line.line.contains(" / ")) {
                    val parts = line.line.split(" / ", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else if (line.line.contains(" | ")) {
                    val parts = line.line.split(" | ", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else {
                    Pair(line.line.trim(), null)
                  }
                }

                val isBlankLine = ogText.isBlank()
                val displayText = if (isBlankLine) ". . ." else ogText
                val hasTranslation = !transText.isNullOrBlank()

                val distanceFromActive =
                  if (state.activeLineIndex >= 0) kotlin.math.abs(index - state.activeLineIndex) else 0

                // Falloff by distance rather than a flat inactive value, so the eye is pulled to
                // the sung line instead of a wall of evenly dim text.
                val lineAlpha by animateFloatAsState(
                  targetValue =
                    when {
                      isActiveLine -> 1.0f
                      distanceFromActive == 1 -> 0.52f
                      distanceFromActive == 2 -> 0.30f
                      distanceFromActive == 3 -> 0.18f
                      else -> 0.10f
                    },
                  animationSpec = tween(durationMillis = if (isActiveLine) 330 else 500, easing = FastOutSlowInEasing),
                  label = "LineAlpha",
                )

                // Depth of field: distant lines defocus. No-op below API 31, which degrades to
                // the alpha falloff alone.
                val lineBlur by animateFloatAsState(
                  targetValue =
                    when {
                      isActiveLine -> 0f
                      distanceFromActive == 1 -> 2f
                      distanceFromActive == 2 -> 5f
                      else -> 12f
                    },
                  animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                  label = "LineBlur",
                )

                val lineScale by animateFloatAsState(
                  targetValue = if (isActiveLine) 1f else 0.92f,
                  animationSpec = spring(dampingRatio = 0.75f, stiffness = 220f),
                  label = "LineScale",
                )

                val lineTranslationY by animateFloatAsState(
                  targetValue = if (isActiveLine) 0f else (index - state.activeLineIndex) * 1.5f,
                  animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
                  label = "LineTranslationY",
                )

                val activeColor = Color.White
                val inactiveColor = Color.White.copy(alpha = 0.45f)

                val lineColor by animateColorAsState(
                  targetValue = if (isActiveLine) activeColor else inactiveColor,
                  animationSpec = tween(durationMillis = 250),
                  label = "LineColor",
                )

                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .blur(radiusX = lineBlur.dp, radiusY = lineBlur.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .graphicsLayer {
                      alpha = lineAlpha
                      translationY = lineTranslationY
                      scaleX = lineScale
                      scaleY = lineScale
                      transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                      onTap?.invoke()
                      if (!isLyricsFullscreen) {
                        val targetSeconds = line.time / 1000f
                        PlaybackSession.command("seek", targetSeconds.toString(), "absolute+exact")
                      }
                    }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  if (isActiveLine && !isBlankLine && !line.words.isNullOrEmpty()) {
                    FlowRow(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.Center,
                      verticalArrangement = Arrangement.Center,
                    ) {
                      line.words.forEachIndexed { wordIndex, word ->
                        val wordEndMs =
                          line.words.getOrNull(wordIndex + 1)?.time?.toLong()
                            ?: activeLyrics.synced.getOrNull(index + 1)?.time?.toLong()
                              ?.coerceAtMost(line.time.toLong() + 8_000L)
                            ?: (word.time + 600).toLong()
                        AnimatedLyricWord(
                          word = word,
                          endTimeMs = wordEndMs,
                          positionMs = smoothPositionMs,
                          activeColor = activeColor,
                          inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                          fontSize = if (isLyricsFullscreen) 26.sp else 22.sp,
                        )
                      }
                    }
                  } else {
                    Text(
                      text = displayText,
                      color = lineColor,
                      fontSize = when {
                        isActiveLine && isLyricsFullscreen -> 26.sp
                        isActiveLine -> 22.sp
                        isLyricsFullscreen -> 21.sp
                        else -> 19.sp
                      },
                      fontWeight = if (isActiveLine) FontWeight.Black else FontWeight.ExtraBold,
                      fontFamily = FontFamily.SansSerif,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }

                  // Render Translation if present (Smaller font size, highlighted together with original when active)
                  if (hasTranslation) {
                    val translationColor by animateColorAsState(
                      targetValue = if (isActiveLine) activeColor.copy(alpha = 0.85f) else inactiveColor.copy(alpha = 0.70f),
                      animationSpec = tween(durationMillis = 250),
                      label = "TranslationColor",
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = transText.orEmpty(),
                      color = translationColor,
                      fontSize = if (isActiveLine) 16.sp else 14.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = FontFamily.SansSerif,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            }
          }

          activeLyrics != null && !activeLyrics.plain.isNullOrEmpty() -> {
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              itemsIndexed(
                items = activeLyrics.plain,
                key = { index, _ -> index },
                contentType = { _, _ -> "lyric_plain_line" },
              ) { _, lineText ->
                val textToDisplay = if (lineText.isBlank()) ". . ." else lineText
                Text(
                  text = textToDisplay,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 20.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.SansSerif,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }

          else -> {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Text(
                text = "No lyrics available for this track.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(8.dp))
              TextButton(onClick = { viewModel.loadLyricsForCurrentTrack(forceRefresh = true) }) {
                Text("Search Online", fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }

      // Edge-to-Edge Sync Timing Adjustments
      AnimatedVisibility(visible = state.lyrics?.synced?.isNotEmpty() == true) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Sync: ${if (state.syncOffsetMs >= 0) "+" else ""}${state.syncOffsetMs / 1000f}s",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(-500) }) { Text("-0.5s", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(-100) }) { Text("-0.1s", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { viewModel.resetLyricsSyncOffset() }) { Text("0s", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(100) }) { Text("+0.1s", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(500) }) { Text("+0.5s", fontWeight = FontWeight.Bold) }
          }
        }
      }
    }
  }
}

/**
 * Interpolates the polled playback position with the display frame clock so letter reveals stay
 * fluid between position updates. Backward jumps (seeks) snap; forward extrapolation is capped so
 * a stalled poll cannot run ahead.
 */
@Composable
private fun rememberSmoothedPositionMs(
  rawPositionMs: Long,
  isPlaying: Boolean,
  speed: Float,
): State<Long> {
  val smoothed = remember { mutableLongStateOf(rawPositionMs) }
  LaunchedEffect(rawPositionMs, isPlaying, speed) {
    smoothed.longValue =
      if (rawPositionMs < smoothed.longValue || rawPositionMs > smoothed.longValue + 1_000L) {
        rawPositionMs
      } else {
        maxOf(smoothed.longValue, rawPositionMs)
      }
    if (!isPlaying) return@LaunchedEffect
    val anchorMs = smoothed.longValue
    var anchorFrameNanos = -1L
    while (true) {
      withFrameNanos { frameNanos ->
        if (anchorFrameNanos < 0L) anchorFrameNanos = frameNanos
        val elapsedMs = (frameNanos - anchorFrameNanos) / 1_000_000f
        smoothed.longValue = anchorMs + (elapsedMs * speed.coerceIn(0.1f, 8f)).toLong().coerceAtMost(800L)
      }
    }
  }
  return smoothed
}

/**
 * Letter-by-letter karaoke: each character is revealed on its own time slot inside the word's
 * duration, rising into place with a pop and a brief glow, colors sweeping dim -> active.
 */
@Composable
private fun AnimatedLyricWord(
  word: SyncedWord,
  endTimeMs: Long,
  positionMs: State<Long>,
  activeColor: Color,
  inactiveColor: Color,
  fontSize: TextUnit = 22.sp,
) {
  val text = word.word
  val textStyle =
    MaterialTheme.typography.headlineSmall.copy(
      fontSize = fontSize,
      fontWeight = FontWeight.Black,
      fontFamily = FontFamily.SansSerif,
    )
  if (text.isEmpty()) {
    Text(text = " ", style = textStyle)
    return
  }

  val startTimeMs = word.time.toLong()
  val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(1L)
  val charSlotMs = durationMs.toFloat() / text.length
  // Each letter animates a bit longer than its slot so neighbours overlap into a wave.
  val charAnimMs = (charSlotMs * 2f).coerceIn(90f, 320f)
  val currentPositionMs by positionMs

  Row(verticalAlignment = Alignment.CenterVertically) {
    text.forEachIndexed { index, character ->
      val charStartMs = startTimeMs + (charSlotMs * index).toLong()
      val rawProgress = ((currentPositionMs - charStartMs) / charAnimMs).coerceIn(0f, 1f)
      val eased = FastOutSlowInEasing.transform(rawProgress)
      val pop = kotlin.math.sin(eased * Math.PI).toFloat()
      Text(
        text = character.toString(),
        color = lerp(inactiveColor, activeColor, eased),
        style =
          if (pop > 0.05f) {
            textStyle.copy(
              shadow =
                Shadow(
                  color = activeColor.copy(alpha = 0.38f * pop),
                  offset = Offset.Zero,
                  blurRadius = 12f * pop,
                ),
            )
          } else {
            textStyle
          },
        modifier =
          Modifier.graphicsLayer {
            translationY = -5.dp.toPx() * pop
            val scale = 1f + 0.14f * pop
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 1f)
          },
      )
    }
    Text(text = " ", style = textStyle)
  }
}
