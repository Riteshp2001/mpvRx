/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.controls.components.MiniAudioVisualizer
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.tv.tvFocusable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedMusicTrackListItem(
  title: String,
  subtitle: String? = null,
  artworkUrl: String? = null,
  albumArtUri: Uri? = null,
  durationSeconds: Long? = null,
  isPlaying: Boolean = false,
  isSelected: Boolean = false,
  coverArtSizeDp: Int = 44,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 3.dp)
      .tvFocusable(AppShapeScale.large)
      .clip(AppShapeScale.large)
      .then(
        if (onLongClick != null) {
          Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
          Modifier.clickable(onClick = onClick)
        }
      ),
    shape = AppShapeScale.large,
    color = when {
      isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
      isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
      else -> Color.Transparent
    },
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(coverArtSizeDp.dp)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        when {
          !artworkUrl.isNullOrBlank() -> {
            RemoteImage(
              url = artworkUrl,
              contentDescription = title,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          albumArtUri != null -> {
            LocalAlbumArtImage(
              uri = albumArtUri,
              contentDescription = title,
              modifier = Modifier.fillMaxSize(),
            )
          }
          else -> {
            Icon(
              imageVector = Icons.RoundedFilled.Audiotrack,
              contentDescription = null,
              tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
        }

        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(24.dp),
            )
          }
        } else if (isPlaying) {
          val paused by PlaybackSession.propBoolean["pause"].collectAsState()
          val isPlaybackActive = paused != true
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
          ) {
            MiniAudioVisualizer(
              isPlaying = isPlaybackActive,
              color = Color.White,
              modifier = Modifier.size(width = 18.dp, height = 16.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
          ),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (durationSeconds != null && durationSeconds > 0) {
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = DateUtils.formatElapsedTime(durationSeconds),
          style = MaterialTheme.typography.labelMedium,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedMusicGridCard(
  title: String,
  subtitle: String? = null,
  artworkUrl: String? = null,
  albumArtUri: Uri? = null,
  fallbackIcon: AppIcon = Icons.RoundedFilled.Audiotrack,
  isCircular: Boolean = false,
  cardWidth: Dp = 145.dp,
  isSelected: Boolean = false,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .width(cardWidth)
      .tvFocusable(if (isCircular) CircleShape else AppShapeScale.extraLarge)
      .clip(if (isCircular) CircleShape else AppShapeScale.extraLarge)
      .then(
        if (onLongClick != null) {
          Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
          Modifier.clickable(onClick = onClick)
        }
      )
      .padding(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(if (isCircular) CircleShape else AppShapeScale.large)
        .background(
          if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
          else MaterialTheme.colorScheme.surfaceVariant
        ),
      contentAlignment = Alignment.Center,
    ) {
      when {
        !artworkUrl.isNullOrBlank() -> {
          RemoteImage(
            url = artworkUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
        albumArtUri != null -> {
          LocalAlbumArtImage(
            uri = albumArtUri,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
          )
        }
        else -> {
          Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
          )
        }
      }

      if (isSelected) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.CheckCircle,
            contentDescription = "Selected",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = title,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurface,
    )

    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
