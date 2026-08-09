/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.torrent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.torrent.TorrentCatalog
import app.gyrolet.mpvrx.domain.torrent.TorrentFileItem
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

private val PickerBackground = Color(0xFF07090D)
private val PickerSurface = Color(0xFF121722)
private val PickerSurfacePressed = Color(0xFF1B2230)
private val PickerText = Color(0xFFF7F8FB)
private val PickerMutedText = Color(0xFFAEB7C7)

@Composable
fun TorrentSelectionScreen(
  state: TorrentSelectionUiState,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onSelect: (Int) -> Unit,
) {
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(PickerBackground),
  ) {
    when (state) {
      TorrentSelectionUiState.Loading -> TorrentLoadingState()
      is TorrentSelectionUiState.Error -> TorrentErrorState(state.message, onRetry)
      is TorrentSelectionUiState.Ready -> TorrentReadyState(state, onSelect)
    }

    Surface(
      modifier =
        Modifier
          .safeDrawingPadding()
          .padding(14.dp)
          .size(44.dp)
          .align(Alignment.TopStart),
      shape = CircleShape,
      color = Color.Black.copy(alpha = 0.42f),
      contentColor = Color.White,
      shadowElevation = 8.dp,
    ) {
      IconButton(
        onClick = onBack,
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.ArrowBack,
          contentDescription = stringResource(R.string.torrent_picker_back),
        )
      }
    }
  }
}

@Composable
private fun TorrentReadyState(
  state: TorrentSelectionUiState.Ready,
  onSelect: (Int) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wideLayout = maxWidth >= 600.dp && maxWidth > maxHeight
    if (wideLayout) {
      Row(Modifier.fillMaxSize()) {
        TorrentHero(
          artwork = state.artwork,
          catalog = state.catalog,
          isLookingUpArtwork = state.isLookingUpArtwork,
          compact = false,
          modifier =
            Modifier
              .weight(0.44f)
              .fillMaxHeight(),
        )
        TorrentFileList(
          state = state,
          onSelect = onSelect,
          modifier =
            Modifier
              .weight(0.56f)
              .fillMaxHeight()
              .safeDrawingPadding(),
        )
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        TorrentHero(
          artwork = state.artwork,
          catalog = state.catalog,
          isLookingUpArtwork = state.isLookingUpArtwork,
          compact = true,
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 280.dp, max = 360.dp),
        )
        TorrentFileList(
          state = state,
          onSelect = onSelect,
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .navigationBarsPadding(),
        )
      }
    }
  }
}

@Composable
private fun TorrentHero(
  artwork: TorrentArtwork,
  catalog: TorrentCatalog,
  isLookingUpArtwork: Boolean,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(modifier.background(PickerBackground)) {
    TorrentBackdropFallback(Modifier.fillMaxSize())
    artwork.backdropUrl?.let { url ->
      RemoteImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        alpha = 0.82f,
      )
    }
    Box(
      Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            0f to Color.Black.copy(alpha = if (compact) 0.08f else 0.18f),
            0.55f to Color.Black.copy(alpha = 0.34f),
            1f to PickerBackground,
          ),
        ),
    )
    if (!compact) {
      Box(
        Modifier
          .fillMaxSize()
          .background(
            Brush.horizontalGradient(
              listOf(Color.Transparent, PickerBackground.copy(alpha = 0.78f)),
            ),
          ),
      )
    }

    Row(
      modifier =
        Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .padding(start = 24.dp, end = 24.dp, bottom = if (compact) 22.dp else 34.dp),
      horizontalArrangement = Arrangement.spacedBy(18.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      TorrentPoster(
        posterUrl = artwork.posterUrl,
        title = artwork.title,
        compact = compact,
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        Text(
          text = artwork.title,
          color = PickerText,
          style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Black,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          artwork.mediaType
            ?.takeIf(String::isNotBlank)
            ?.let { MediaPill(it.replaceFirstChar { char -> char.titlecase() }) }
          artwork.releaseYear?.takeIf(String::isNotBlank)?.let(::MediaPill)
          MediaPill(
            pluralStringResource(
              R.plurals.torrent_picker_playable_count,
              catalog.playableFiles.size,
              catalog.playableFiles.size,
            ),
          )
        }
        artwork.description?.let { description ->
          Text(
            text = description,
            color = PickerText.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
            maxLines = if (compact) 2 else 5,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (isLookingUpArtwork) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(13.dp),
              color = PickerMutedText,
              strokeWidth = 1.5.dp,
            )
            Text(
              text = stringResource(R.string.torrent_picker_finding_details),
              color = PickerMutedText,
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TorrentPoster(
  posterUrl: String?,
  title: String,
  compact: Boolean,
) {
  val width = if (compact) 82.dp else 128.dp
  Surface(
    modifier =
      Modifier
        .width(width)
        .aspectRatio(2f / 3f)
        .shadow(18.dp, RoundedCornerShape(16.dp)),
    color = PickerSurface,
    shape = RoundedCornerShape(16.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      TorrentBackdropFallback(Modifier.fillMaxSize())
      if (posterUrl != null) {
        RemoteImage(
          url = posterUrl,
          contentDescription = title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Icon(
          imageVector = Icons.RoundedFilled.Movie,
          contentDescription = null,
          modifier = Modifier.size(if (compact) 34.dp else 46.dp),
          tint = Color.White.copy(alpha = 0.86f),
        )
      }
    }
  }
}

@Composable
private fun MediaPill(text: String) {
  Surface(
    color = Color.Black.copy(alpha = 0.44f),
    contentColor = PickerText,
    shape = RoundedCornerShape(100.dp),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun TorrentFileList(
  state: TorrentSelectionUiState.Ready,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .background(PickerBackground)
        .padding(horizontal = 20.dp),
  ) {
    Spacer(Modifier.height(24.dp))
    Text(
      text = stringResource(R.string.torrent_picker_choose_title),
      color = PickerText,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = pluralStringResource(
        R.plurals.torrent_picker_playable_count,
        state.catalog.playableFiles.size,
        state.catalog.playableFiles.size,
      ),
      modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
      color = PickerMutedText,
      style = MaterialTheme.typography.bodyMedium,
    )

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      itemsIndexed(
        items = state.catalog.playableFiles,
        key = { _, file -> file.index },
      ) { position, file ->
        TorrentFileCard(
          file = file,
          position = position,
          enabled = state.launchingFileIndex == null,
          launching = state.launchingFileIndex == file.index,
          onClick = { onSelect(file.index) },
        )
      }
      item { Spacer(Modifier.height(14.dp)) }
    }
  }
}

@Composable
private fun TorrentFileCard(
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
        .clip(RoundedCornerShape(18.dp))
        .clickable(enabled = enabled, onClick = onClick),
    color = if (launching) PickerSurfacePressed else PickerSurface,
    contentColor = PickerText,
    shape = RoundedCornerShape(18.dp),
    tonalElevation = if (launching) 4.dp else 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
      Surface(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(14.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = episode?.shortLabel ?: (position + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        episode?.let { parsed ->
          Text(
            text =
              if (parsed.season != null) {
                stringResource(R.string.torrent_picker_episode_label, parsed.season, parsed.episode)
              } else {
                stringResource(R.string.torrent_picker_single_episode_label, parsed.episode)
              },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          text = file.name,
          color = PickerText,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val path = file.path.replace('\\', '/')
        if (path != file.name) {
          Text(
            text = path.substringBeforeLast('/', ""),
            color = PickerMutedText.copy(alpha = 0.76f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text =
            listOfNotNull(
              extension.takeIf(String::isNotBlank),
              formatTorrentBytes(file.size),
            ).joinToString("  •  "),
          color = PickerMutedText,
          style = MaterialTheme.typography.labelMedium,
        )
      }

      if (launching) {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          color = MaterialTheme.colorScheme.primary,
          strokeWidth = 2.dp,
        )
      } else {
        Surface(
          modifier = Modifier.size(38.dp),
          color = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          shape = CircleShape,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.RoundedFilled.PlayArrow,
              contentDescription = stringResource(R.string.torrent_picker_play_file, file.name),
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TorrentLoadingState() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    TorrentBackdropFallback(Modifier.fillMaxSize())
    Column(
      modifier = Modifier.padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(46.dp),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 3.dp,
      )
      Text(
        text = stringResource(R.string.torrent_picker_preparing_title),
        color = PickerText,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.torrent_picker_preparing_description),
        color = PickerMutedText,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun TorrentErrorState(
  message: String,
  onRetry: () -> Unit,
) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    TorrentBackdropFallback(Modifier.fillMaxSize())
    Column(
      modifier =
        Modifier
          .widthIn(max = 420.dp)
          .padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        contentColor = PickerText,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(Icons.RoundedFilled.CloudDownload, contentDescription = null, modifier = Modifier.size(34.dp))
        }
      }
      Text(
        text = stringResource(R.string.torrent_picker_error_title),
        color = PickerText,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = message,
        color = PickerMutedText,
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(
        onClick = onRetry,
        modifier = Modifier.padding(top = 8.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
      ) {
        Icon(Icons.RoundedFilled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.torrent_picker_retry))
      }
    }
  }
}

@Composable
private fun TorrentBackdropFallback(modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier.background(
        Brush.linearGradient(
          colors = listOf(Color(0xFF0B1325), Color(0xFF271343), Color(0xFF07101B)),
        ),
      ),
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val radius = size.minDimension * 0.34f
      drawCircle(
        color = Color(0xFF8D6BFF).copy(alpha = 0.13f),
        radius = radius,
        center = Offset(size.width * 0.72f, size.height * 0.28f),
      )
      repeat(3) { ring ->
        drawCircle(
          color = Color.White.copy(alpha = 0.055f - ring * 0.01f),
          radius = size.minDimension * (0.18f + ring * 0.09f),
          center = center,
          style = Stroke(width = 2.dp.toPx()),
        )
      }
      drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = Offset(size.width * 0.12f, size.height * 0.62f),
        end = Offset(size.width * 0.88f, size.height * 0.38f),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
      )
    }
  }
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
