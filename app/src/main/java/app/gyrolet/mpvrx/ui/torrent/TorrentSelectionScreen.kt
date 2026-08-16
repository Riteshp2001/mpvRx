/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.torrent

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.torrent.TorrentFileItem
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

private const val VIEWED_TORRENT_FILES_PREFS = "torrent_viewed_files"

@Composable
fun TorrentSelectionScreen(
  state: TorrentSelectionUiState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onSelect: (Int) -> Unit,
) {
  when (state) {
    TorrentSelectionUiState.Loading -> TorrentLoadingScreen(onBack)
    is TorrentSelectionUiState.Error -> TorrentErrorScreen(state.message, onBack, onRetry)
    is TorrentSelectionUiState.Ready -> TorrentReadyScreen(state, onBack, onSelect)
  }
}

@Composable
private fun TorrentReadyScreen(
  state: TorrentSelectionUiState.Ready,
  onBack: () -> Unit,
  onSelect: (Int) -> Unit,
) {
  BackHandler { onBack() }
  val artwork = state.artwork
  val hasBackdrop = !artwork.backdropUrl.isNullOrBlank()
  val context = LocalContext.current
  val viewedPreferences =
    remember(context) {
      context.getSharedPreferences(VIEWED_TORRENT_FILES_PREFS, Context.MODE_PRIVATE)
    }
  var viewedFileIndices by
    remember(state.catalog.infoHash) {
      mutableStateOf(loadViewedFileIndices(viewedPreferences, state.catalog.infoHash))
    }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var isSearchOpen by rememberSaveable { mutableStateOf(false) }
  var sortDescending by rememberSaveable { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      if (hasBackdrop) {
        RemoteImage(
          url = artwork.backdropUrl!!,
          contentDescription = null,
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .blur(20.dp),
          contentScale = ContentScale.Crop,
          alpha = 0.4f,
        )
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                ),
              ),
        )
      }

      Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(horizontal = 4.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = Icons.RoundedFilled.ArrowBack,
              contentDescription = stringResource(android.R.string.cancel),
            )
          }
          Text(
            text = stringResource(R.string.torrent_picker_choose_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
          )
          if (state.launchingFileIndex != null) {
            CircularProgressIndicator(
              modifier =
                Modifier
                  .size(22.dp)
                  .padding(end = 8.dp),
              strokeWidth = 2.dp,
            )
          }
        }

        // Hero banner + metadata
        if (hasBackdrop || artwork.title.isNotBlank()) {
          TorrentHeroBanner(artwork)
        }

        val displayedFiles =
          remember(state.catalog.playableFiles, searchQuery, sortDescending) {
            val filtered =
              if (searchQuery.isBlank()) {
                state.catalog.playableFiles
              } else {
                val query = searchQuery.trim()
                state.catalog.playableFiles.filter { file ->
                  file.name.contains(query, ignoreCase = true) ||
                    file.path.contains(query, ignoreCase = true) ||
                    (file.index + 1).toString() == query
                }
              }
            if (sortDescending) filtered.reversed() else filtered
          }

        // File list header
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text =
                pluralStringResource(
                  R.plurals.torrent_picker_playable_count,
                  displayedFiles.size,
                  displayedFiles.size,
                ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )

            if (state.catalog.playableFiles.size > 1) {
              IconButton(
                onClick = { isSearchOpen = !isSearchOpen },
                modifier = Modifier.size(36.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = stringResource(R.string.ui_search_episodes),
                  modifier = Modifier.size(20.dp),
                  tint =
                    if (isSearchOpen || searchQuery.isNotBlank()) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
              }

              IconButton(
                onClick = { sortDescending = !sortDescending },
                modifier = Modifier.size(36.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.SwapVert,
                  contentDescription =
                    stringResource(
                      if (sortDescending) R.string.ui_sort_descending else R.string.ui_sort_ascending,
                    ),
                  modifier = Modifier.size(20.dp),
                  tint =
                    if (sortDescending) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
              }
            }
          }

          if (isSearchOpen && state.catalog.playableFiles.size > 1) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(top = 4.dp, bottom = 4.dp),
              placeholder = {
                Text(
                  stringResource(R.string.ui_search_episodes),
                  style = MaterialTheme.typography.bodySmall,
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              },
              trailingIcon = {
                if (searchQuery.isNotBlank()) {
                  IconButton(onClick = { searchQuery = "" }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = "Clear",
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              textStyle = MaterialTheme.typography.bodySmall,
            )
          }
        }

        // File list
        if (displayedFiles.isEmpty()) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.ui_no_matching_episodes),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(
            modifier =
              Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            itemsIndexed(
              items = displayedFiles,
              key = { _, file -> file.index },
              contentType = { _, _ -> "torrent_file_row" },
            ) { position, file ->
              TorrentFileRow(
                file = file,
                position = position,
                enabled = state.launchingFileIndex == null,
                launching = state.launchingFileIndex == file.index,
                viewed = file.index in viewedFileIndices,
                onClick = {
                  val updatedViewedFiles = viewedFileIndices + file.index
                  viewedFileIndices = updatedViewedFiles
                  saveViewedFileIndices(viewedPreferences, state.catalog.infoHash, updatedViewedFiles)
                  onSelect(file.index)
                },
              )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
          }
        }
      }
    }
  }
}

@Composable
private fun TorrentHeroBanner(artwork: TorrentArtwork) {
  var expanded by remember { mutableStateOf(false) }
  val hasBackdrop = !artwork.backdropUrl.isNullOrBlank()
  val hasPoster = !artwork.posterUrl.isNullOrBlank()
  val hasDescription = !artwork.description.isNullOrBlank()
  val metadata =
    listOfNotNull(
      artwork.releaseYear,
      artwork.mediaType,
    ).joinToString("  •  ")

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .then(if (hasBackdrop) Modifier else Modifier.padding(top = 8.dp)),
  ) {
    if (hasBackdrop) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp)),
      ) {
        RemoteImage(
          url = artwork.backdropUrl!!,
          contentDescription = artwork.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(140.dp)
              .align(Alignment.BottomCenter)
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                ),
              ),
        )
        Row(
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          if (hasPoster) {
            Box(
              modifier =
                Modifier
                  .width(84.dp)
                  .aspectRatio(2f / 3f)
                  .clip(RoundedCornerShape(10.dp)),
            ) {
              RemoteImage(
                url = artwork.posterUrl!!,
                contentDescription = artwork.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
              )
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = artwork.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            if (metadata.isNotBlank()) {
              Text(
                text = metadata,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
              )
            }
          }
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
      ) {
        if (hasPoster) {
          Box(
            modifier =
              Modifier
                .width(84.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
          ) {
            RemoteImage(
              url = artwork.posterUrl!!,
              contentDescription = artwork.title,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = artwork.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (metadata.isNotBlank()) {
            Text(
              text = metadata,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 6.dp),
            )
          }
        }
      }
    }

    if (hasDescription) {
      Text(
        text = artwork.description!!,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier =
          Modifier
            .padding(top = 10.dp)
            .clickable { expanded = !expanded },
      )
    }
  }
}

@Composable
private fun TorrentFileRow(
  file: TorrentFileItem,
  position: Int,
  enabled: Boolean,
  launching: Boolean,
  viewed: Boolean,
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

      when {
        launching -> {
          CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
          )
        }
        viewed -> {
          Icon(
            imageVector = Icons.RoundedFilled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

@Composable
private fun TorrentLoadingScreen(onBack: () -> Unit) {
  BackHandler { onBack() }
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
      Spacer(modifier = Modifier.height(20.dp))
      Text(
        text = stringResource(R.string.torrent_picker_preparing_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.torrent_picker_preparing_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(24.dp))
      TextButton(onClick = onBack) {
        Text(stringResource(android.R.string.cancel))
      }
    }
  }
}

@Composable
private fun TorrentErrorScreen(
  message: String,
  onBack: () -> Unit,
  onRetry: () -> Unit,
) {
  BackHandler { onBack() }
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = stringResource(R.string.torrent_picker_error_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(24.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) {
          Text(stringResource(android.R.string.cancel))
        }
        TextButton(onClick = onRetry) {
          Text(stringResource(R.string.torrent_picker_retry))
        }
      }
    }
  }
}

private fun loadViewedFileIndices(
  preferences: SharedPreferences,
  infoHash: String,
): Set<Int> =
  preferences
    .getStringSet(infoHash, emptySet())
    .orEmpty()
    .mapNotNull(String::toIntOrNull)
    .toSet()

private fun saveViewedFileIndices(
  preferences: SharedPreferences,
  infoHash: String,
  indices: Set<Int>,
) {
  preferences
    .edit()
    .putStringSet(infoHash, indices.map(Int::toString).toSet())
    .apply()
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
