package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.theme.spacing
import kotlinx.collections.immutable.ImmutableList
import org.koin.compose.koinInject

data class PlaylistItem(
  val uri: Uri,
  val title: String,
  val index: Int,
  val isPlaying: Boolean,
  val progressPercent: Float = 0f, // 0-100, progress of video watched
  val isWatched: Boolean = false,  // True if video is fully watched (100%)
  val path: String = "", // Video path for thumbnail loading
  val duration: String = "", // Duration in formatted string (e.g., "10:30")
  val resolution: String = "", // Resolution (e.g., "1920x1080")
)

private fun playlistThumbnailKey(item: PlaylistItem): String {
  val base = item.path.ifBlank { item.uri.toString() }
  return "playlist-thumb|$base"
}

private fun buildPlaylistThumbnailRequest(
  context: android.content.Context,
  item: PlaylistItem,
): ImageRequest {
  val cacheKey = playlistThumbnailKey(item)
  return ImageRequest.Builder(context)
    .data(item.uri)
    .memoryCacheKey(cacheKey)
    .diskCacheKey(cacheKey)
    .crossfade(true)
    .build()
}

@Composable
fun PlaylistSheet(
  playlist: ImmutableList<PlaylistItem>,
  onDismissRequest: () -> Unit,
  onItemClick: (PlaylistItem) -> Unit,
  totalCount: Int = playlist.size,
  isM3UPlaylist: Boolean = false,
  playerPreferences: app.gyrolet.mpvrx.preferences.PlayerPreferences,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val imageLoader = koinInject<ImageLoader>()

  val accentColor = MaterialTheme.colorScheme.primary

  // Check portrait mode
  val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

  // Portrait mode => list mode
  val isListModePreference by playerPreferences.playlistViewMode.collectAsState()
  var isListMode by remember { mutableStateOf(if (isPortrait) true else isListModePreference) }

  LaunchedEffect(isPortrait) {
    if (isPortrait && !isListMode) {
      isListMode = true
    }
  }

  // Update preference when view mode changes (only in landscape)
  LaunchedEffect(isListMode) {
    if (!isPortrait && isListMode != isListModePreference) {
      playerPreferences.playlistViewMode.set(isListMode)
    }
  }

  // Scroll state for the playlist
  val lazyListState = rememberLazyListState()

  // Find the currently playing item index - tracks changes in playlist items
  val playingItemIndex by remember {
    derivedStateOf {
      playlist.indexOfFirst { it.isPlaying }
    }
  }

  // Scroll to the currently playing item when the playing item changes or when sheet opens
  LaunchedEffect(playingItemIndex) {
    if (playingItemIndex >= 0) {
      lazyListState.animateScrollToItem(playingItemIndex)
    }
  }

  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val sheetWidth = if (isListMode) {
    if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      640.dp
    } else {
      420.dp
    }
  } else {
    screenWidth * 0.85f
  }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = Modifier.fillMaxWidth(),
    customMaxWidth = sheetWidth,
    customMaxHeight = if (isPortrait) LocalConfiguration.current.screenHeightDp.dp * 0.5f else null,
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = Color.Transparent,
      shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
      ),
      tonalElevation = 0.dp,
    ) {
      Column(
        modifier = modifier.padding(
          vertical = MaterialTheme.spacing.smaller,
          horizontal = if (!isListMode) MaterialTheme.spacing.medium else 0.dp
        )
      ) {
        // Header showing current playlist info with toggle button
        val currentItem = playlist.getOrNull(playingItemIndex)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(
              horizontal = if (isListMode) MaterialTheme.spacing.medium else 0.dp,
              vertical = MaterialTheme.spacing.small,
            ),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
            modifier = Modifier.weight(1f)
          ) {
            if (currentItem != null) {
              Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleSmall.copy(
                  fontWeight = FontWeight.Bold,
                  color = accentColor,
                ),
              )
              Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(
              text = "$totalCount items",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          // Toggle button for list/grid view (only in landscape)
          if (!isPortrait) {
            IconButton(
              onClick = { isListMode = !isListMode }
            ) {
              Icon(
                imageVector = if (isListMode) Icons.Default.GridView else Icons.Default.ViewList,
                contentDescription = if (isListMode) "Switch to Grid View" else "Switch to List View",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        // Conditional rendering based on view mode
        if (isListMode) {
          // Vertical list mode (original implementation)
          LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth()
          ) {
            items(playlist, key = { it.uri.toString() }) { item ->
              PlaylistTrackListItem(
                item = item,
                imageLoader = imageLoader,
                onClick = { onItemClick(item) },
                skipThumbnail = isM3UPlaylist,
                accentColor = accentColor
              )
            }
          }
        } else {
          // Horizontal grid mode
          LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(
              horizontal = if (isListMode) MaterialTheme.spacing.medium else 0.dp,
              vertical = MaterialTheme.spacing.small
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)
          ) {
            items(playlist, key = { it.uri.toString() }) { item ->
              PlaylistTrackGridItem(
                item = item,
                imageLoader = imageLoader,
                onClick = {
                  onItemClick(item)
                },
                skipThumbnail = isM3UPlaylist,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun PlaylistTrackListItem(
  item: PlaylistItem,
  imageLoader: ImageLoader,
  onClick: () -> Unit,
  skipThumbnail: Boolean = false,
  accentColor: Color,
  modifier: Modifier = Modifier,
) {
  // Use theme colors dynamically
  val accentSecondary = MaterialTheme.colorScheme.tertiary
  val context = LocalContext.current

  val borderModifier = if (item.isPlaying) {
    Modifier.border(
      width = 2.dp,
      brush = Brush.linearGradient(listOf(accentColor, accentSecondary)),
      shape = RoundedCornerShape(12.dp),
    )
  } else {
    Modifier
  }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        horizontal = MaterialTheme.spacing.medium,
        vertical = MaterialTheme.spacing.extraSmall,
      )
      .clip(RoundedCornerShape(12.dp))
      .then(borderModifier)
      .clickable(onClick = onClick),
    color = if (item.isPlaying) {
      MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
    } else {
      Color.Transparent
    },
    shape = RoundedCornerShape(12.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(MaterialTheme.spacing.smaller),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      // Thumbnail with simple background, episode number, and progress
      Box(
        modifier = Modifier
          .width(100.dp)
          .height(56.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Movie,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(24.dp),
        )
        if (!skipThumbnail) {
          AsyncImage(
            model = remember(item.uri, item.path) { buildPlaylistThumbnailRequest(context, item) },
            imageLoader = imageLoader,
            contentDescription = "Thumbnail",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        }

        // Video number badge in top-left with better visibility
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .background(
              color = Color.Black.copy(alpha = 0.7f),
              shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = "${item.index + 1}",
            style = MaterialTheme.typography.labelMedium.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
            ),
            color = Color.White,
          )
        }
      }

      // Title and info
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.title,
          style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Normal,
            color = if (item.isPlaying) {
              accentColor
            } else if (item.isWatched) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        // Duration and resolution chips - always show with loading state if empty
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // Duration chip
          if (item.duration.isNotEmpty()) {
            Surface(
              color = if (item.isPlaying) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.duration,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            LoadingChip(width = 40.dp)
          }
          
          // Resolution chip
          if (item.resolution.isNotEmpty()) {
            Surface(
              color = if (item.isPlaying) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.resolution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            LoadingChip(width = 60.dp)
          }
        }
      }

      // Status badges
      when {
        item.isPlaying -> {
          Surface(
            color = accentColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
          ) {
            Text(
              text = "Playing",
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
              ),
            )
          }
        }

      }
    }
  }
}

@Composable
fun PlaylistTrackGridItem(
  item: PlaylistItem,
  imageLoader: ImageLoader,
  onClick: () -> Unit,
  skipThumbnail: Boolean = false,
  modifier: Modifier = Modifier,
) {
  // Use theme colors dynamically
  val accentColor = MaterialTheme.colorScheme.primary
  val accentSecondary = MaterialTheme.colorScheme.tertiary
  val context = LocalContext.current

  val borderModifier = if (item.isPlaying) {
    Modifier.border(
      width = 2.dp,
      brush = Brush.linearGradient(listOf(accentColor, accentSecondary)),
      shape = RoundedCornerShape(12.dp),
    )
  } else {
    Modifier
  }

  // YouTube-style vertical card
  Surface(
    modifier = modifier
      .width(200.dp)
      .clip(RoundedCornerShape(12.dp))
      .then(borderModifier)
      .clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.smaller),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      // Thumbnail with 16:9 aspect ratio
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(112.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Movie,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(32.dp),
        )
        if (!skipThumbnail) {
          AsyncImage(
            model = remember(item.uri, item.path) { buildPlaylistThumbnailRequest(context, item) },
            imageLoader = imageLoader,
            contentDescription = "Thumbnail",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        }

        // Video number badge in top-left
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .background(
              color = Color.Black.copy(alpha = 0.7f),
              shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = "${item.index + 1}",
            style = MaterialTheme.typography.labelMedium.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp,
            ),
            color = Color.White,
          )
        }

        // Duration badge in bottom-right
        if (item.duration.isNotEmpty()) {
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(6.dp)
              .background(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(4.dp),
              )
              .padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            Text(
              text = item.duration,
              style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
              ),
              color = Color.White,
            )
          }
        } else {
          // Loading duration badge
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(6.dp)
          ) {
            LoadingChip(width = 40.dp, height = 18.dp, isDark = true)
          }
        }

        // Playing indicator overlay
        if (item.isPlaying) {
          Box(
            modifier = Modifier
              .matchParentSize()
              .background(
                brush = Brush.verticalGradient(
                  colors = listOf(
                    accentColor.copy(alpha = 0.3f),
                    accentColor.copy(alpha = 0.1f),
                  )
                )
              )
          )
        }
      }

      // Title and metadata
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.title,
          modifier = Modifier.height(44.dp),
          style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp,
            color = if (item.isPlaying) {
              accentColor
            } else if (item.isWatched) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        // Resolution and status
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Resolution chip
          if (item.resolution.isNotEmpty()) {
            Surface(
              color = if (item.isPlaying) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.resolution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            LoadingChip(width = 60.dp)
          }

          if (item.isPlaying) {
            Surface(
              color = accentColor.copy(alpha = 0.15f),
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = "Playing",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = accentColor,
                ),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun LoadingChip(
  width: androidx.compose.ui.unit.Dp,
  height: androidx.compose.ui.unit.Dp = 18.dp,
  isDark: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
  val shimmerTranslate = infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "shimmer"
  )

  val baseColor = if (isDark) {
    Color.White.copy(alpha = 0.1f)
  } else {
    MaterialTheme.colorScheme.surfaceContainerHighest
  }
  
  val shimmerColor = if (isDark) {
    Color.White.copy(alpha = 0.2f)
  } else {
    MaterialTheme.colorScheme.surfaceContainerHigh
  }

  Box(
    modifier = modifier
      .width(width)
      .height(height)
      .clip(RoundedCornerShape(4.dp))
      .background(
        brush = Brush.linearGradient(
          colors = listOf(
            baseColor,
            shimmerColor,
            baseColor,
          ),
          start = Offset(shimmerTranslate.value - 200f, 0f),
          end = Offset(shimmerTranslate.value, 0f)
        )
      )
  )
}




