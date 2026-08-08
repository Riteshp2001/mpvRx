/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel

@Composable
fun LyricsSheet(
  viewModel: PlayerViewModel,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.lyricsUiState.collectAsState()
  val mediaTitle by PlaybackSession.propString["media-title"].collectAsState()
  val artistName by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
  val displayTitle = mediaTitle?.takeIf { it.isNotBlank() } ?: "Current Track"
  val displayArtist = artistName?.takeIf { it.isNotBlank() } ?: ""

  val listState = rememberLazyListState()

  // Auto-scroll to active line
  LaunchedEffect(state.activeLineIndex) {
    if (state.activeLineIndex >= 0) {
      val targetItem = (state.activeLineIndex - 2).coerceAtLeast(0)
      runCatching {
        listState.animateScrollToItem(targetItem)
      }
    }
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Top drag handle
      Box(
        modifier = Modifier
          .width(36.dp)
          .height(4.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
      )

      Spacer(modifier = Modifier.height(12.dp))

      // Header title + Source Switcher
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.RoundedFilled.Lyrics,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = displayTitle,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (displayArtist.isNotBlank()) {
            Text(
              text = displayArtist,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        IconButton(onClick = onDismiss) {
          Icon(
            imageVector = Icons.RoundedFilled.Close,
            contentDescription = "Close",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Source Switcher Row (Embedded vs Online)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val hasEmbedded = state.embeddedLyrics != null && state.embeddedLyrics?.isValid() == true
        val hasOnline = state.onlineLyrics != null && state.onlineLyrics?.isValid() == true

        FilterChip(
          selected = state.selectedSource == LyricsSourceType.EMBEDDED || state.selectedSource == LyricsSourceType.LOCAL,
          onClick = { viewModel.switchLyricsSource(LyricsSourceType.EMBEDDED) },
          label = {
            Text(if (state.embeddedLyrics?.sourceType == LyricsSourceType.LOCAL) "Local (.lrc)" else "Embedded")
          },
          enabled = hasEmbedded || state.selectedSource == LyricsSourceType.EMBEDDED,
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        )

        FilterChip(
          selected = state.selectedSource == LyricsSourceType.ONLINE,
          onClick = { viewModel.switchLyricsSource(LyricsSourceType.ONLINE) },
          label = {
            Text("Online (LRCLIB)")
          },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        )

        if (state.isLoading) {
          Spacer(modifier = Modifier.width(8.dp))
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Lyrics Content
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(340.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f))
          .padding(horizontal = 16.dp, vertical = 12.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          activeLyrics != null && !activeLyrics.synced.isNullOrEmpty() -> {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              itemsIndexed(activeLyrics.synced) { index, line ->
                val isActive = index == state.activeLineIndex
                val textColor by animateColorAsState(
                  targetValue = if (isActive) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                  },
                  animationSpec = tween(durationMillis = 250),
                  label = "LyricTextColor",
                )

                Text(
                  text = line.line,
                  color = textColor,
                  fontSize = if (isActive) 20.sp else 16.sp,
                  fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                  textAlign = TextAlign.Center,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                      val targetSeconds = line.time / 1000f
                      PlaybackSession.command("seek", targetSeconds.toString(), "absolute+exact")
                    }
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                )
              }
            }
          }

          activeLyrics != null && !activeLyrics.plain.isNullOrEmpty() -> {
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              itemsIndexed(activeLyrics.plain) { _, lineText ->
                Text(
                  text = lineText,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 16.sp,
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
                text = "No lyrics found for this track.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(8.dp))
              TextButton(onClick = { viewModel.loadLyricsForCurrentTrack(forceRefresh = true) }) {
                Text("Search Online")
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Footer: Sync Offset Controls
      AnimatedVisibility(visible = state.lyrics?.synced?.isNotEmpty() == true) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Sync: ${if (state.syncOffsetMs >= 0) "+" else ""}${state.syncOffsetMs / 1000f}s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(-500) }) { Text("-0.5s") }
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(-100) }) { Text("-0.1s") }
            TextButton(onClick = { viewModel.resetLyricsSyncOffset() }) { Text("0s") }
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(100) }) { Text("+0.1s") }
            TextButton(onClick = { viewModel.adjustLyricsSyncOffset(500) }) { Text("+0.5s") }
          }
        }
      }
    }
  }
}
