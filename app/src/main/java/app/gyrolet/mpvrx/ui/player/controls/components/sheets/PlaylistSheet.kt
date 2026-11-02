/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.utils.ReorderFeedback
import app.gyrolet.mpvrx.ui.utils.rememberReorderFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerGlassSurfaceActive
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.controls.components.MiniAudioVisualizer
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.connectedShape
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.abs
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

fun PlaylistItem.toVideo(): Video {
  val cleanPath =
    if (path.startsWith("file://", ignoreCase = true)) {
      runCatching { Uri.parse(path).path }.getOrNull() ?: path.removePrefix("file://")
    } else {
      path.ifBlank { uri.path.orEmpty().ifBlank { uri.toString() } }
    }
  return Video(
    id = uri.toString().hashCode().toLong(),
    title = title,
    displayName = title,
    path = cleanPath,
    uri = uri,
    duration = 0L,
    durationFormatted = duration,
    size = 0L,
    sizeFormatted = "",
    dateModified = 0L,
    dateAdded = 0L,
    mimeType = if (isAudio) "audio/*" else "video/*",
    bucketId = "",
    bucketDisplayName = "",
    width = 0,
    height = 0,
    fps = 0f,
    resolution = resolution,
    isAudio = isAudio,
  )
}

data class PlaylistItem(
  val uri: Uri,
  val title: String,
  val artist: String = "",
  val index: Int,
  val isPlaying: Boolean,
  val progressPercent: Float = 0f, // 0-100, progress of video watched
  val isWatched: Boolean = false, // True if video is fully watched (100%)
  val path: String = "", // Video path for thumbnail loading
  val duration: String = "", // Duration in formatted string (e.g., "10:30")
  val resolution: String = "", // Resolution (e.g., "1920x1080")
  val isAudio: Boolean = false,
  val tvgLogo: String = "", // M3U channel logo URL for fallback
  val networkConnectionId: Long? = null,
  val networkPath: String = "",
  val queueEntryId: Long = index.toLong(),
)

@Composable
private fun PlaylistThumbnail(
  item: PlaylistItem,
  thumbnailRepository: ThumbnailRepository,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
) {
  val revision by thumbnailRepository.cacheRevision.collectAsState()
  val generator by koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>().thumbnailGenerator.collectAsState()
  val isLocal = app.gyrolet.mpvrx.domain.thumbnail.isLocalArtworkSource(item.path.ifBlank { item.uri.toString() })
  val hasArtwork = !isLocal && item.tvgLogo.isNotBlank()
  val isYouTubeArtwork =
    remember(item.tvgLogo) {
      val host = runCatching { Uri.parse(item.tvgLogo).host.orEmpty().lowercase() }.getOrDefault("")
      host == "i.ytimg.com" || host.endsWith(".ytimg.com")
    }
  val cleanPath =
    remember(item.path, item.uri) {
      val raw = item.path.ifBlank { item.uri.toString() }
      if (raw.startsWith("file://", ignoreCase = true)) {
        runCatching { Uri.parse(raw).path }.getOrNull() ?: raw.removePrefix("file://")
      } else {
        raw
      }
    }
  val video =
    remember(item.uri, cleanPath, item.title, item.isAudio) {
      Video(
        id =
          item.uri
            .toString()
            .hashCode()
            .toLong(),
        title = item.title,
        displayName = item.title,
        path = cleanPath,
        uri = item.uri,
        duration = 0L,
        durationFormatted = item.duration,
        size = 0L,
        sizeFormatted = "",
        dateModified = 0L,
        dateAdded = 0L,
        mimeType = if (item.isAudio) "audio/*" else "video/*",
        bucketId = "",
        bucketDisplayName = "",
        width = 0,
        height = 0,
        fps = 0f,
        resolution = item.resolution,
        isAudio = item.isAudio,
      )
    }
  val (thumbWidth, thumbHeight) =
    remember(item.isAudio) {
      if (item.isAudio) 512 to 512 else PLAYLIST_THUMBNAIL_WIDTH to PLAYLIST_THUMBNAIL_HEIGHT
    }
  val thumbnailKey =
    remember(video, item.networkConnectionId, item.networkPath, thumbWidth, thumbHeight, generator, revision) {
      if (item.networkConnectionId != null && item.networkPath.isNotBlank()) {
        "network-playlist|${item.networkConnectionId}|${item.networkPath}|$thumbWidth|$thumbHeight|$revision"
      } else {
        "${item.uri}|$thumbWidth|$thumbHeight|$generator|$revision"
      }
    }
  var bitmap by remember(thumbnailKey) {
    mutableStateOf(
      if (!hasArtwork && (item.networkConnectionId == null || item.networkPath.isBlank())) {
        thumbnailRepository.peekThumbnailFromMemory(video, thumbWidth, thumbHeight)
      } else {
        null
      },
    )
  }

  LaunchedEffect(thumbnailKey) {
    if (!hasArtwork && bitmap == null) {
      bitmap =
        withContext(Dispatchers.IO) {
          val connectionId = item.networkConnectionId
          if (connectionId != null && item.networkPath.isNotBlank()) {
            thumbnailRepository.getThumbnailForNetworkSource(
              connectionId = connectionId,
              path = item.networkPath,
              widthPx = thumbWidth,
              heightPx = thumbHeight,
            )
          } else {
            thumbnailRepository.getThumbnail(video, thumbWidth, thumbHeight)
          }
        }
    }
  }

  val currentImageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
  val isEdgeToEdge = item.isAudio || isYouTubeArtwork
  if (hasArtwork) {
    RemoteImage(
      maxDimensionPx = maxOf(thumbWidth, thumbHeight),
      url = item.tvgLogo,
      contentDescription = contentDescription,
      contentScale = if (isEdgeToEdge) ContentScale.Crop else contentScale,
      modifier = if (isEdgeToEdge) modifier else modifier.padding(4.dp),
    )
  } else if (currentImageBitmap != null) {
    androidx.compose.foundation.Image(
      bitmap = currentImageBitmap,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
    )
  }
}

private const val PLAYLIST_THUMBNAIL_WIDTH = 512
private const val PLAYLIST_THUMBNAIL_HEIGHT = 288

@Composable
fun PlaylistSheet(
  playlist: ImmutableList<PlaylistItem>,
  onDismissRequest: () -> Unit,
  onItemClick: (PlaylistItem) -> Unit,
  onReorder: ((Int, Int) -> Unit)? = null,
  totalCount: Int = playlist.size,
  isM3UPlaylist: Boolean = false,
  playerPreferences: app.gyrolet.mpvrx.preferences.PlayerPreferences,
  isSwipeActive: Boolean = false,
  swipeOffset: Float = 0f,
  isAudioOnly: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val configuration = LocalConfiguration.current
  val thumbnailRepository = koinInject<ThumbnailRepository>()

  val accentColor = MaterialTheme.colorScheme.primary

  // Check portrait mode
  val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

  // Portrait mode => list mode (v2.5.0 behavior: grid toggle is landscape-only,
  // portrait is always forced to list so phones/tablet portrait stay readable)
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
  val playingItemIndex = remember(playlist) { playlist.indexOfFirst { it.isPlaying } }
  val playingItemKey = playlist.getOrNull(playingItemIndex)?.queueEntryId

  var hasPositionedInitialPlayingItem by remember { mutableStateOf(false) }

  // Position the initial item without competing with the sheet entrance animation. Animate only
  // actual track changes: moving the playing row must not scroll it back to the top.
  LaunchedEffect(playingItemKey) {
    if (playingItemIndex >= 0) {
      if (hasPositionedInitialPlayingItem) {
        val distanceFromVisibleItem = playingItemIndex - lazyListState.firstVisibleItemIndex
        if (abs(distanceFromVisibleItem) > PLAYLIST_SCROLL_APPROACH_ITEMS) {
          val approachOffset =
            if (distanceFromVisibleItem > 0) {
              PLAYLIST_SCROLL_APPROACH_ITEMS
            } else {
              -PLAYLIST_SCROLL_APPROACH_ITEMS
            }
          val approachIndex = playingItemIndex - approachOffset
          lazyListState.scrollToItem(approachIndex.coerceIn(0, playlist.lastIndex))
        }
        lazyListState.animateScrollToItem(playingItemIndex)
      } else {
        lazyListState.scrollToItem(playingItemIndex)
        hasPositionedInitialPlayingItem = true
      }
    }
  }

  var showAddToPlaylistDialog by rememberSaveable { mutableStateOf(false) }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = Modifier.fillMaxWidth(),
    customMaxHeight = if (isPortrait) configuration.screenHeightDp.dp * 0.55f else null,
    isSwipeActive = isSwipeActive,
    swipeOffset = swipeOffset,
    useFrostedGlass = true,
  ) {
    Column(
      modifier =
        modifier.padding(
          vertical = MaterialTheme.spacing.smaller,
          horizontal = if (!isListMode) MaterialTheme.spacing.medium else 0.dp,
        ),
    ) {
        // Header showing current playlist info with toggle button
        val currentItem = playlist.getOrNull(playingItemIndex)
        Row(
          modifier =
            Modifier
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
            modifier = Modifier.weight(1f),
          ) {
            if (currentItem != null) {
              Text(
                text =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_now_playing),
                style =
                  MaterialTheme.typography.titleSmall.copy(
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

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            IconButton(
              onClick = { showAddToPlaylistDialog = true },
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.PlaylistAdd,
                contentDescription = stringResource(R.string.save_queue_as_playlist),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            // Toggle button for list/grid view (only in landscape) — v2.5.0 behavior
            if (!isPortrait) {
              IconButton(
                onClick = { isListMode = !isListMode },
              ) {
                Icon(
                  imageVector = if (isListMode) Icons.RoundedFilled.GridView else Icons.RoundedFilled.ViewList,
                  contentDescription = if (isListMode) "Switch to Grid View" else "Switch to List View",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        // Conditional rendering based on view mode
        if (isListMode) {
          // Vertical list mode (original implementation)
          // Keep a stable drag preview while queue metadata updates in the background.
          var displayPlaylist by remember { mutableStateOf(playlist) }
          var dragInProgress by remember { mutableStateOf(false) }
          var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }
          LaunchedEffect(playlist, dragInProgress, pendingOrder) {
            if (!dragInProgress) {
              val expected = pendingOrder
              val incomingOrder = playlist.map { it.queueEntryId }
              if (expected == null || incomingOrder == expected || incomingOrder.toSet() != expected.toSet()) {
                pendingOrder = null
                displayPlaylist = playlist
              }
            }
          }

          val showDragHandle = onReorder != null && !isM3UPlaylist && displayPlaylist.size > 1

          var dragStartIndex by remember { mutableIntStateOf(-1) }
          var dragEndIndex by remember { mutableIntStateOf(-1) }
          val reorderFeedback = rememberReorderFeedback()

          val reorderableLazyListState =
            rememberReorderableLazyListState(lazyListState) { from, to ->
              if (showDragHandle) {
                if (dragStartIndex == -1) {
                  dragStartIndex = from.index
                }
                dragEndIndex = to.index
                if (from.index in displayPlaylist.indices && to.index in displayPlaylist.indices && from.index != to.index) {
                  displayPlaylist = displayPlaylist.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                  }.toImmutableList()
                  reorderFeedback.move(from.index, to.index)
                }
              }
            }

          LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            items(displayPlaylist.size, key = { index -> displayPlaylist[index].queueEntryId }) { index ->
              val item = displayPlaylist[index]
              val rowShape = connectedShape(index, displayPlaylist.size, ConnectedLayout.Vertical)
              if (showDragHandle) {
                ReorderableItem(
                  reorderableLazyListState,
                  key = item.queueEntryId,
                  animateItemModifier = Modifier,
                ) { isDragging ->
                  val isDraggingPrev = remember { mutableStateOf(false) }
                  LaunchedEffect(isDragging) {
                    if (isDraggingPrev.value && !isDragging) {
                      if (dragStartIndex != -1 && dragEndIndex != -1 && dragStartIndex != dragEndIndex) {
                        pendingOrder = displayPlaylist.map { it.queueEntryId }
                        onReorder.invoke(dragStartIndex, dragEndIndex)
                      }
                      dragInProgress = false
                      dragStartIndex = -1
                      dragEndIndex = -1
                    }
                    isDraggingPrev.value = isDragging
                  }

                  PlaylistTrackListItem(
                    item = item,
                    thumbnailRepository = thumbnailRepository,
                    onClick = { onItemClick(item.copy(index = index)) },
                    skipThumbnail = false,
                    accentColor = accentColor,
                    isAudioOnly = isAudioOnly,
                    displayPosition = index,
                    shape = rowShape,
                    dragHandle = {
                      DragHandle(
                        scope = this,
                        isDragging = isDragging,
                        feedback = reorderFeedback,
                        onDragStart = { dragInProgress = true },
                      )
                    },
                  )
                }
              } else {
                PlaylistTrackListItem(
                  item = item,
                  thumbnailRepository = thumbnailRepository,
                  onClick = { onItemClick(item.copy(index = index)) },
                  skipThumbnail = false,
                  accentColor = accentColor,
                  isAudioOnly = isAudioOnly,
                  displayPosition = index,
                  shape = rowShape,
                )
              }
            }
          }
        } else {
          // Horizontal grid mode
          LazyRow(
            state = lazyListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding =
              PaddingValues(
                horizontal = if (isListMode) MaterialTheme.spacing.medium else 0.dp,
                vertical = MaterialTheme.spacing.small,
              ),
            horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            items(playlist.size, key = { playlist[it].queueEntryId }) { index ->
              val item = playlist[index]
              PlaylistTrackGridItem(
                item = item,
                shape = connectedShape(index, playlist.size, ConnectedLayout.Horizontal),
                thumbnailRepository = thumbnailRepository,
                onClick = {
                  onItemClick(item)
                },
                skipThumbnail = false,
                isAudioOnly = isAudioOnly,
              )
            }
          }
        }
      }
    }

  if (showAddToPlaylistDialog && playlist.isNotEmpty()) {
    val queueVideos = remember(playlist) { playlist.map { it.toVideo() } }
    AddToPlaylistDialog(
      isOpen = true,
      videos = queueVideos,
      onDismiss = { showAddToPlaylistDialog = false },
      onSuccess = { showAddToPlaylistDialog = false },
    )
  }
}

@Composable
private fun DragHandle(
  scope: ReorderableCollectionItemScope,
  isDragging: Boolean,
  feedback: ReorderFeedback,
  onDragStart: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      with(scope) {
        modifier
          .size(40.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(
            if (isDragging) MaterialTheme.colorScheme.primaryContainer
            else playlistUnselectedRowColor(),
          )
          .draggableHandle(
            interactionSource = feedback.interactions,
            onDragStarted = {
              onDragStart()
              feedback.start()
            },
          )
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.DragHandle,
      contentDescription = "Drag to reorder",
      tint =
        if (isDragging) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.size(24.dp),
    )
  }
}

@Composable
fun PlaylistTrackListItem(
  item: PlaylistItem,
  thumbnailRepository: ThumbnailRepository,
  onClick: () -> Unit,
  skipThumbnail: Boolean = false,
  accentColor: Color,
  isAudioOnly: Boolean = false,
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(12.dp),
  displayPosition: Int = item.index,
  dragHandle: @Composable () -> Unit = {},
) {
  val isAudioItem = item.isAudio || isAudioOnly
  val effectiveItem =
    remember(item, isAudioItem) {
      if (item.isAudio != isAudioItem) item.copy(isAudio = isAudioItem) else item
    }

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(
          horizontal = MaterialTheme.spacing.medium,
          vertical = 0.dp,
        ).clip(shape)
        .clickable(onClick = onClick),
    color = if (effectiveItem.isPlaying) {
      animatedPlayerSheetSelectionColor(true, label = "playlistItemSelection")
    } else {
      playlistUnselectedRowColor()
    },
    shape = shape,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(MaterialTheme.spacing.smaller),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      // Thumbnail with simple background, episode number, and progress
      Box(
        modifier =
          Modifier
            .then(
              if (isAudioItem) {
                Modifier.size(56.dp)
              } else {
                Modifier.width(100.dp).height(56.dp)
              },
            ).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (isAudioItem) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.Videocam,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(24.dp),
        )
        if (!skipThumbnail) {
          PlaylistThumbnail(
            item = effectiveItem,
            thumbnailRepository = thumbnailRepository,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        }

        // Video number badge in top-left with better visibility
        Box(
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .padding(6.dp)
              .background(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
              ).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = "${displayPosition + 1}",
            style =
              MaterialTheme.typography.labelMedium.copy(
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
          text = item.title.stripExtension(),
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Normal,
              color =
                if (item.isPlaying) {
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

        if (item.artist.isNotBlank()) {
          Text(
            text = item.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        // Duration and resolution chips - always show with loading state if empty
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // Duration chip
          if (item.duration.isNotEmpty()) {
            Surface(
              color = playlistChipColor(item.isPlaying),
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.duration,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style =
                  MaterialTheme.typography.labelSmall.copy(
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
              color = playlistChipColor(item.isPlaying),
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.resolution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style =
                  MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                  ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else if (!item.isAudio) {
            LoadingChip(width = 60.dp)
          }
        }
      }

      // Status badges
      when {
        item.isPlaying -> {
          val paused by PlaybackSession.propBoolean["pause"].collectAsState()
          val isPlaybackActive = paused != true
          Surface(
            color = accentColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
          ) {
            Box(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              contentAlignment = Alignment.Center,
            ) {
              MiniAudioVisualizer(
                isPlaying = isPlaybackActive,
                color = accentColor,
                modifier = Modifier.size(width = 16.dp, height = 14.dp),
              )
            }
          }
        }
      }

      dragHandle()
    }
  }
}

@Composable
fun PlaylistTrackGridItem(
  item: PlaylistItem,
  thumbnailRepository: ThumbnailRepository,
  onClick: () -> Unit,
  skipThumbnail: Boolean = false,
  isAudioOnly: Boolean = false,
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(12.dp),
) {
  val isAudioItem = item.isAudio || isAudioOnly
  val effectiveItem =
    remember(item, isAudioItem) {
      if (item.isAudio != isAudioItem) item.copy(isAudio = isAudioItem) else item
    }

  val accentColor = MaterialTheme.colorScheme.primary

  // YouTube-style vertical card
  Surface(
    modifier =
      modifier
        .width(200.dp)
        .clip(shape)
        .clickable(onClick = onClick),
    color = if (effectiveItem.isPlaying) {
      animatedPlayerSheetSelectionColor(true, label = "playlistItemSelection")
    } else {
      playlistUnselectedRowColor()
    },
    shape = shape,
  ) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.smaller),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      // Thumbnail with 1:1 aspect ratio for audio, fixed height for video
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .then(
              if (isAudioItem) {
                Modifier.aspectRatio(1f)
              } else {
                Modifier.height(112.dp)
              },
            ).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (isAudioItem) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.Videocam,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.size(32.dp),
        )
        if (!skipThumbnail) {
          PlaylistThumbnail(
            item = effectiveItem,
            thumbnailRepository = thumbnailRepository,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        }

        // Video number badge in top-left
        Box(
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .padding(6.dp)
              .background(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
              ).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = "${item.index + 1}",
            style =
              MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
              ),
            color = Color.White,
          )
        }

        // Duration badge in bottom-right
        if (item.duration.isNotEmpty()) {
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(
                  color = Color.Black.copy(alpha = 0.8f),
                  shape = RoundedCornerShape(4.dp),
                ).padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            Text(
              text = item.duration,
              style =
                MaterialTheme.typography.labelSmall.copy(
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Medium,
                ),
              color = Color.White,
            )
          }
        } else {
          // Loading duration badge
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
          ) {
            LoadingChip(width = 40.dp, height = 18.dp, isDark = true)
          }
        }

        // Playing indicator overlay
        if (item.isPlaying) {
          Box(
            modifier =
              Modifier
                .matchParentSize()
                .background(
                  brush =
                    Brush.verticalGradient(
                      colors =
                        listOf(
                          accentColor.copy(alpha = 0.3f),
                          accentColor.copy(alpha = 0.1f),
                        ),
                    ),
                ),
          )
        }
      }

      // Title and metadata
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.title.stripExtension(),
          modifier = Modifier.height(44.dp),
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontWeight = if (item.isPlaying) FontWeight.Bold else FontWeight.Medium,
              fontSize = 14.sp,
              color =
                if (item.isPlaying) {
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

        if (item.artist.isNotBlank()) {
          Text(
            text = item.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        // Resolution and status
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Resolution chip
          if (item.resolution.isNotEmpty()) {
            Surface(
              color = playlistChipColor(item.isPlaying),
              shape = RoundedCornerShape(4.dp),
            ) {
              Text(
                text = item.resolution,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style =
                  MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                  ),
                color = if (item.isPlaying) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else if (!item.isAudio) {
            LoadingChip(width = 60.dp)
          }

          if (item.isPlaying) {
            val paused by PlaybackSession.propBoolean["pause"].collectAsState()
            val isPlaybackActive = paused != true
            Surface(
              color = accentColor.copy(alpha = 0.15f),
              shape = RoundedCornerShape(4.dp),
            ) {
              Box(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
              ) {
                MiniAudioVisualizer(
                  isPlaying = isPlaybackActive,
                  color = accentColor,
                  modifier = Modifier.size(width = 14.dp, height = 12.dp),
                )
              }
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
  val shimmerTranslate =
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1000f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1200, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "shimmer",
    )

  val baseColor =
    if (isDark) {
      Color.White.copy(alpha = 0.1f)
    } else {
      MaterialTheme.colorScheme.surfaceContainerHighest
    }

  val shimmerColor =
    if (isDark) {
      Color.White.copy(alpha = 0.2f)
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    }

  Box(
    modifier =
      modifier
        .width(width)
        .height(height)
        .clip(RoundedCornerShape(4.dp))
        .background(
          brush =
            Brush.linearGradient(
              colors =
                listOf(
                  baseColor,
                  shimmerColor,
                  baseColor,
                ),
              start = Offset(shimmerTranslate.value - 200f, 0f),
              end = Offset(shimmerTranslate.value, 0f),
            ),
        ),
  )
}

private fun String.stripExtension(): String {
  val dotIndex = lastIndexOf('.')
  if (dotIndex <= 0) return this
  val ext = substring(dotIndex + 1)
  return if (ext.length in 2..5 && ext.none { it.isWhitespace() }) substring(0, dotIndex) else this
}

/**
 * Unselected playlist rows read the shared frosted palette on a liquid-glass sheet and
 * keep their plain, non-glass container when the sheet is solid.
 */
@Composable
private fun playlistUnselectedRowColor(): Color =
  if (LocalPlayerGlassSurfaceActive.current) {
    playerSheetSelectionColor(false)
  } else {
    MaterialTheme.colorScheme.surfaceContainerLow
  }

/** Small duration/resolution/status chips, translated the same way as the rows. */
@Composable
private fun playlistChipColor(isPlaying: Boolean): Color =
  when {
    isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    LocalPlayerGlassSurfaceActive.current -> playerSheetSelectionColor(false)
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
  }

private const val PLAYLIST_SCROLL_APPROACH_ITEMS = 6