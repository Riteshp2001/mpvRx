/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.torrent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.torrent.TorrentFileItem
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes

@Composable
fun TorrentSelectionScreen(
  state: TorrentSelectionUiState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onSelect: (Int) -> Unit,
) {
  when (state) {
    TorrentSelectionUiState.Loading -> TorrentLoadingDialog(onBack)
    is TorrentSelectionUiState.Error -> TorrentErrorDialog(state.message, onBack, onRetry)
    is TorrentSelectionUiState.Ready -> TorrentFilePickerDialog(state, onBack, onSelect)
  }
}

@Composable
private fun TorrentFilePickerDialog(
  state: TorrentSelectionUiState.Ready,
  onBack: () -> Unit,
  onSelect: (Int) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onBack,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          text = stringResource(R.string.torrent_picker_choose_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = pluralStringResource(
            R.plurals.torrent_picker_playable_count,
            state.catalog.playableFiles.size,
            state.catalog.playableFiles.size,
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    text = {
      LazyColumn(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        itemsIndexed(
          items = state.catalog.playableFiles,
          key = { _, file -> file.index },
        ) { position, file ->
          TorrentFileRow(
            file = file,
            position = position,
            enabled = state.launchingFileIndex == null,
            launching = state.launchingFileIndex == file.index,
            onClick = { onSelect(file.index) },
          )
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onBack, enabled = state.launchingFileIndex == null) {
        Text(stringResource(android.R.string.cancel))
      }
    },
  )
}

@Composable
private fun TorrentFileRow(
  file: TorrentFileItem,
  position: Int,
  enabled: Boolean,
  launching: Boolean,
  onClick: () -> Unit,
) {
  val episode = parseEpisode(file.name) ?: parseEpisode(file.path)
  val extension = file.name.substringAfterLast('.', "").uppercase().take(5)

  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = if (launching) 2.dp else 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = episode?.shortLabel ?: (position + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        episode?.let { parsed ->
          Text(
            text =
              if (parsed.season != null) {
                stringResource(R.string.torrent_picker_episode_label, parsed.season, parsed.episode)
              } else {
                stringResource(R.string.torrent_picker_single_episode_label, parsed.episode)
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          text = file.name,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            listOfNotNull(
              extension.takeIf(String::isNotBlank),
              formatTorrentBytes(file.size),
            ).joinToString("  •  "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }

      if (launching) {
        CircularProgressIndicator(
          modifier = Modifier.size(22.dp),
          strokeWidth = 2.dp,
        )
      }
    }
  }
}

@Composable
private fun TorrentLoadingDialog(onBack: () -> Unit) {
  AlertDialog(
    onDismissRequest = onBack,
    title = { Text(stringResource(R.string.torrent_picker_preparing_title)) },
    text = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        Text(
          text = stringResource(R.string.torrent_picker_preparing_description),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onBack) {
        Text(stringResource(android.R.string.cancel))
      }
    },
  )
}

@Composable
private fun TorrentErrorDialog(
  message: String,
  onBack: () -> Unit,
  onRetry: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onBack,
    title = { Text(stringResource(R.string.torrent_picker_error_title)) },
    text = { Text(message) },
    confirmButton = {
      TextButton(onClick = onRetry) {
        Text(stringResource(R.string.torrent_picker_retry))
      }
    },
    dismissButton = {
      TextButton(onClick = onBack) {
        Text(stringResource(android.R.string.cancel))
      }
    },
  )
}

private data class ParsedEpisode(
  val shortLabel: String,
  val season: Int?,
  val episode: Int,
)

private val seasonEpisodePattern = Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?:[^a-z0-9]|$)")
private val episodePattern = Regex("(?i)(?:^|[^a-z0-9])(?:episode|ep)[ ._-]*(\\d{1,3})(?:[^a-z0-9]|$)")

private fun parseEpisode(value: String): ParsedEpisode? {
  seasonEpisodePattern.find(value)?.let { match ->
    val season = match.groupValues[1].toIntOrNull() ?: return@let
    val episode = match.groupValues[2].toIntOrNull() ?: return@let
    return ParsedEpisode(
      shortLabel = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}",
      season = season,
      episode = episode,
    )
  }
  episodePattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { episode ->
    return ParsedEpisode(
      shortLabel = "E${episode.toString().padStart(2, '0')}",
      season = null,
      episode = episode,
    )
  }
  return null
}
