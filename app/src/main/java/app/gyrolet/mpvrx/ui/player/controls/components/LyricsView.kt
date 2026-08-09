/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
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

  val currentPosMs = remember(precisePosition, state.syncOffsetMs) {
    (precisePosition * 1000).toLong() + state.syncOffsetMs
  }

  // Auto-scroll to active line
  LaunchedEffect(state.activeLineIndex, isLyricsFullscreen) {
    if (state.activeLineIndex >= 0) {
      val targetItem = (state.activeLineIndex - 2).coerceAtLeast(0)
      runCatching {
        listState.animateScrollToItem(targetItem)
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
          .fillMaxWidth(),
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
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
              itemsIndexed(activeLyrics.synced) { index, line ->
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

                val lineAlpha by animateFloatAsState(
                  targetValue = if (isActiveLine) 1.0f else 0.45f,
                  animationSpec = tween(durationMillis = 250),
                  label = "LineAlpha",
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
                    .graphicsLayer {
                      alpha = lineAlpha
                      translationY = lineTranslationY
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
                ) {
                  if (isActiveLine && !isBlankLine && !line.words.isNullOrEmpty()) {
                    // Apple Music Style Word-by-Word Sliding Highlight
                    FlowRow(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.Start,
                      verticalArrangement = Arrangement.Center,
                    ) {
                      line.words.forEach { word ->
                        val isWordPassed = currentPosMs >= word.time
                        val wordColor by animateColorAsState(
                          targetValue = if (isWordPassed) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                          animationSpec = tween(durationMillis = 200),
                          label = "WordColor",
                        )

                        val wordSlideY by animateFloatAsState(
                          targetValue = if (isWordPassed) -2f else 0f,
                          animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                          label = "WordSlideY",
                        )

                        Text(
                          text = word.word + " ",
                          color = wordColor,
                          fontSize = 22.sp,
                          fontWeight = FontWeight.Black,
                          fontFamily = FontFamily.SansSerif,
                          modifier = Modifier.graphicsLayer {
                            translationY = wordSlideY
                          },
                        )
                      }
                    }
                  } else {
                    Text(
                      text = displayText,
                      color = lineColor,
                      fontSize = if (isActiveLine) 22.sp else 19.sp,
                      fontWeight = if (isActiveLine) FontWeight.Black else FontWeight.ExtraBold,
                      fontFamily = FontFamily.SansSerif,
                      textAlign = TextAlign.Start,
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
                      textAlign = TextAlign.Start,
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
              itemsIndexed(activeLyrics.plain) { _, lineText ->
                val textToDisplay = if (lineText.isBlank()) ". . ." else lineText
                Text(
                  text = textToDisplay,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 20.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.SansSerif,
                  textAlign = TextAlign.Start,
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
