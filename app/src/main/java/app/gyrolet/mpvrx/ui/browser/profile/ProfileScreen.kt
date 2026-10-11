/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.profile

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.cards.PlaylistCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistViewModel
import app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedItem
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedViewModel
import app.gyrolet.mpvrx.ui.framecapture.SnapshotDetailScreen
import app.gyrolet.mpvrx.ui.framecapture.SnapshotLibraryViewModel
import app.gyrolet.mpvrx.ui.framecapture.SnapshotScreen
import app.gyrolet.mpvrx.ui.framecapture.toDetailItem
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.ProfileWatchStatistics
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.onWallpaper
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

private const val SHELF_LIMIT = 15
private const val AVATAR_FILE_PREFIX = "profile_avatar_"
private const val AVATAR_SIZE_PX = 512
private val SHELF_VIDEO_CARD_WIDTH = 214.dp
private val SHELF_AUDIO_CARD_WIDTH = 120.dp

/** Personal hub that folds Recents, Playlists and Snapshots into one bottom-navigation tab. */
@Serializable
object ProfileScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val advancedPreferences = koinInject<AdvancedPreferences>()
    val browserPreferences = koinInject<BrowserPreferences>()
    val enableRecentlyPlayed by advancedPreferences.enableRecentlyPlayed.collectAsState()
    val profileName by appearancePreferences.profileName.collectAsState()
    val profileImagePath by appearancePreferences.profileImagePath.collectAsState()
    val showRecentThumbnails by browserPreferences.recentView.showThumbnails.collectAsState()
    val playlistManualGrid by browserPreferences.playlistView.manualGridColumnsEnabled.collectAsState()
    val playlistGridColumnsPortrait by browserPreferences.playlistView.gridColumnsPortrait.collectAsState()
    val playlistGridColumnsLandscape by browserPreferences.playlistView.gridColumnsLandscape.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxPlaylistColumns = playlistGridColumnLimit(configuration.screenWidthDp, true)
    val requestedPlaylistColumns =
      if (isLandscape) playlistGridColumnsLandscape else playlistGridColumnsPortrait
    val playlistColumns =
      if (playlistManualGrid && requestedPlaylistColumns > 0) {
        requestedPlaylistColumns.coerceIn(1, maxPlaylistColumns)
      } else {
        maxPlaylistColumns
      }
    val profilePlaylistCardWidth =
      remember(configuration.screenWidthDp, playlistColumns) {
        val totalSpacing = 12 * (playlistColumns - 1)
        ((configuration.screenWidthDp - 24 - totalSpacing).toFloat() / playlistColumns)
          .coerceAtLeast(120f)
          .dp
      }
    val navigationBarHeight = LocalNavigationBarHeight.current

    val recentsViewModel: RecentlyPlayedViewModel = viewModel(factory = RecentlyPlayedViewModel.factory(application))
    val playlistViewModel: PlaylistViewModel = viewModel(factory = PlaylistViewModel.factory(application))
    val snapshotViewModel: SnapshotLibraryViewModel = viewModel(factory = SnapshotLibraryViewModel.factory(application))

    LifecycleResumeEffect(playlistViewModel) {
      playlistViewModel.refresh()
      onPauseOrDispose { }
    }

    val recentItems by recentsViewModel.recentItems.collectAsState()
    val playlists by playlistViewModel.playlistsWithCount.collectAsState()
    val snapshotLibrary by snapshotViewModel.library.collectAsState()
    val snapshotThumbnails by snapshotViewModel.thumbnailCache.collectAsState()

    val recentShelf = remember(recentItems) { recentItems.take(SHELF_LIMIT) }
    val recentVideos =
      remember(recentShelf) { recentShelf.filterIsInstance<RecentlyPlayedItem.VideoItem>().map { it.video } }
    val playbackInfo = rememberSwipePlaybackInfo(recentVideos)
    val snapshots = remember(snapshotLibrary.allCaptures) { snapshotLibrary.allCaptures.sortedByDescending { it.capturedAt } }

    var activeVideoItem by remember { mutableStateOf<RecentlyPlayedItem.VideoItem?>(null) }
    var playlistToDelete by remember { mutableStateOf<app.gyrolet.mpvrx.database.entities.PlaylistEntity?>(null) }
    var showProfileDialog by rememberSaveable { mutableStateOf(false) }
    val isRefreshing = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val missingFileMessage = stringResource(R.string.ui_recent_file_no_longer_exists)
    val showHeaderAvatar by remember {
      derivedStateOf {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 160
      }
    }
    val heroCollapseProgress by remember {
      derivedStateOf {
        if (listState.firstVisibleItemIndex > 0) 1f
        else (listState.firstVisibleItemScrollOffset / 180f).coerceIn(0f, 1f)
      }
    }
    val reduceMotion = AppMotion.shouldReduceMotion()
    val headerAlphaSpec = if (reduceMotion) AppMotion.ReducedAlpha else AppMotion.Effect.Alpha
    val headerOffsetSpec = if (reduceMotion) AppMotion.ReducedOffset else AppMotion.Spatial.ExpressiveOffset
    val headerScaleSpec = if (reduceMotion) AppMotion.ReducedAlpha else AppMotion.Spatial.ExpressiveDefault

    fun playRecent(video: Video) {
      scope.launch {
        val playable = recentsViewModel.resolvePlayableRecentVideo(video)
        if (playable != null) {
          MediaUtils.playFile(playable, context, "recently_played")
        } else {
          Toast.makeText(context, missingFileMessage, Toast.LENGTH_SHORT).show()
        }
      }
    }

    Scaffold(
      containerColor = wallpaperAwareBackgroundColor(),
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.ui_profile),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onCancelSelection = { },
          onSettingsClick = { backStack.navigateTo(PreferencesScreen) },
          customTitle = {
            AnimatedContent(
              targetState = showHeaderAvatar,
              transitionSpec = {
                if (reduceMotion) {
                  fadeIn(headerAlphaSpec).togetherWith(fadeOut(headerAlphaSpec))
                } else if (targetState) {
                  (fadeIn(headerAlphaSpec) + slideInVertically(headerOffsetSpec) { it / 2 } + scaleIn(headerScaleSpec, initialScale = 0.86f))
                    .togetherWith(fadeOut(headerAlphaSpec) + slideOutVertically(headerOffsetSpec) { -it / 2 })
                } else {
                  (fadeIn(headerAlphaSpec) + slideInVertically(headerOffsetSpec) { -it / 2 })
                    .togetherWith(fadeOut(headerAlphaSpec) + slideOutVertically(headerOffsetSpec) { it / 2 } + scaleOut(headerScaleSpec, targetScale = 0.86f))
                }
              },
              label = "profileHeaderTransition",
            ) { isCollapsed ->
              if (isCollapsed) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                  modifier =
                    Modifier
                      .clip(CircleShape)
                      .clickable {
                        scope.launch { listState.animateScrollToItem(0) }
                      }
                      .padding(vertical = 4.dp, horizontal = 4.dp),
                ) {
                  ProfileAvatar(
                    size = 36.dp,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentDescription = profileName,
                  )
                  Text(
                    text = profileName.ifBlank { stringResource(R.string.ui_profile) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface.onWallpaper(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              } else {
                Text(
                  text = stringResource(R.string.ui_profile),
                  style = MaterialTheme.typography.headlineMedium,
                  fontWeight = FontWeight.ExtraBold,
                  color = MaterialTheme.colorScheme.primary.onWallpaper(),
                  modifier = Modifier.padding(start = 8.dp),
                )
              }
            }
          },
        )
      },
    ) { paddingValues ->
      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
          recentsViewModel.refresh()
          playlistViewModel.refresh().join()
        },
        listState = listState,
        modifier = Modifier.fillMaxSize().padding(paddingValues),
      ) {
        val isTablet = configuration.smallestScreenWidthDp >= 600
        val horizontalShelfPadding = if (isTablet) 24.dp else 16.dp

        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = 0.dp, bottom = navigationBarHeight + 16.dp),
          verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
          item(key = "profile_overview") {
            ProfileOverviewCard(
              name = profileName,
              recentCount = recentItems.size,
              playlistCount = playlists.size,
              snapshotCount = snapshots.size,
              showNetwork = true,
              collapseProgress = heroCollapseProgress,
              onEditProfile = {
                haptics.tick()
                showProfileDialog = true
              },
              onRecents = { backStack.navigateTo(RecentlyPlayedScreen) },
              onPlaylists = { backStack.navigateTo(PlaylistScreen) },
              onSnapshots = { backStack.navigateTo(SnapshotScreen) },
              onNetwork = { backStack.navigateTo(NetworkStreamingScreen) },
            )
          }

          item(key = "recents_header") {
            SectionHeader(
              title = stringResource(R.string.pref_advanced_enable_recently_played_title),
              count = if (enableRecentlyPlayed) recentItems.size else null,
              onViewAll = { backStack.navigateTo(RecentlyPlayedScreen) },
            )
          }
          item(key = "recents_shelf") {
            when {
              !enableRecentlyPlayed ->
                ShelfEmptyCard(
                  icon = Icons.RoundedFilled.History,
                  title = stringResource(R.string.ui_recently_played_disabled),
                  message = stringResource(R.string.ui_recently_played_disabled_message),
                )

              recentShelf.isEmpty() ->
                ShelfEmptyCard(
                  icon = Icons.RoundedFilled.History,
                  title = stringResource(R.string.ui_no_recently_played_videos),
                  message = stringResource(R.string.profile_recents_empty_message),
                )

              else ->
                LazyRow(
                  contentPadding = PaddingValues(horizontal = horizontalShelfPadding),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(
                    recentShelf,
                    key = { item ->
                      when (item) {
                        is RecentlyPlayedItem.VideoItem -> "video_${item.video.path}"
                        is RecentlyPlayedItem.PlaylistItem -> "playlist_${item.playlist.id}"
                      }
                    },
                  ) { item ->
                    when (item) {
                      is RecentlyPlayedItem.VideoItem -> {
                        val info = playbackInfo[item.video.path]
                        RecentVideoCard(
                          video = item.video,
                          progress = info?.progressPercentage?.takeUnless { info.isWatched },
                          showThumbnail = showRecentThumbnails,
                          onClick = { playRecent(item.video) },
                          onLongClick = {
                            haptics.pickup()
                            activeVideoItem = item
                          },
                        )
                      }

                      is RecentlyPlayedItem.PlaylistItem -> {
                        PlaylistCard(
                          playlist = item.playlist,
                          itemCount = item.videoCount,
                          onClick = { backStack.navigateTo(PlaylistDetailScreen(item.playlist.id)) },
                          onLongClick = { },
                          onThumbClick = { backStack.navigateTo(PlaylistDetailScreen(item.playlist.id)) },
                          modifier = Modifier.width(SHELF_VIDEO_CARD_WIDTH),
                          isGridMode = true,
                          onDeleteClick =
                            if (playlistViewModel.isProtectedPlaylist(item.playlist)) {
                              null
                            } else {
                              { playlistToDelete = item.playlist }
                            },
                        )
                      }
                    }
                  }
                }
            }
          }

          item(key = "playlists_header") {
            SectionHeader(
              title = stringResource(R.string.ui_playlists),
              count = playlists.size,
              onViewAll = { backStack.navigateTo(PlaylistScreen) },
            )
          }
          item(key = "playlists_shelf") {
            if (playlists.isEmpty()) {
              ShelfEmptyCard(
                icon = Icons.RoundedFilled.Subscriptions,
                title = stringResource(R.string.ui_no_playlists_yet),
                message = stringResource(R.string.profile_playlists_empty_message),
              )
            } else {
              LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalShelfPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(playlists.take(SHELF_LIMIT), key = { it.playlist.id }) { entry ->
                  PlaylistCard(
                    playlist = entry.playlist,
                    itemCount = entry.itemCount,
                    onClick = { backStack.navigateTo(PlaylistDetailScreen(entry.playlist.id)) },
                    onLongClick = { },
                    onThumbClick = { backStack.navigateTo(PlaylistDetailScreen(entry.playlist.id)) },
                    modifier = Modifier.width(SHELF_VIDEO_CARD_WIDTH),
                    isGridMode = true,
                    onDeleteClick =
                      if (playlistViewModel.isProtectedPlaylist(entry.playlist)) {
                        null
                      } else {
                        { playlistToDelete = entry.playlist }
                      },
                  )
                }
              }
            }
          }

          item(key = "snapshots_header") {
            SectionHeader(
              title = stringResource(R.string.ui_snapshots),
              count = snapshots.size,
              onViewAll = { backStack.navigateTo(SnapshotScreen) },
            )
          }
          item(key = "snapshots_shelf") {
            if (snapshots.isEmpty()) {
              ShelfEmptyCard(
                icon = Icons.RoundedFilled.Image,
                title = stringResource(R.string.snapshot_empty_title),
                message = stringResource(R.string.snapshot_empty_message),
              )
            } else {
              LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalShelfPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                itemsIndexed(snapshots.take(SHELF_LIMIT), key = { _, capture -> capture.id }) { index, capture ->
                  LaunchedEffect(capture.id) { snapshotViewModel.loadThumbnail(capture) }
                  SnapshotShelfCard(
                    capture = capture,
                    thumbnail = snapshotThumbnails[capture.id],
                    onClick = {
                      backStack.navigateTo(
                        SnapshotDetailScreen(
                          captures = snapshots.map { it.toDetailItem() },
                          initialIndex = index,
                        ),
                      )
                    },
                  )
                }
              }
            }
          }
          item(key = "watch_statistics") {
            ProfileWatchStatistics()
          }
        }
      }
    }

    activeVideoItem?.let { item ->
      RecentVideoActionsSheet(
        video = item.video,
        onDismiss = { activeVideoItem = null },
        onPlay = {
          activeVideoItem = null
          playRecent(item.video)
        },
        onShare = {
          activeVideoItem = null
          MediaUtils.shareVideos(context, listOf(item.video))
        },
        onRemove = {
          activeVideoItem = null
          scope.launch { recentsViewModel.deleteVideosFromHistory(listOf(item.video)) }
        },
      )
    }

    if (showProfileDialog) {
      ProfileEditDialog(
        initialName = profileName,
        hasPhoto = profileImagePath.isNotBlank(),
        onDismiss = { showProfileDialog = false },
        onSave = { name, newPhoto, removePhoto ->
          showProfileDialog = false
          appearancePreferences.profileName.set(name.trim())
          if (newPhoto != null || removePhoto) {
            scope.launch {
              val path = withContext(Dispatchers.IO) { storeProfileAvatar(context, newPhoto) }
              appearancePreferences.profileImagePath.set(path)
            }
          }
        },
      )
    }

    playlistToDelete?.let { target ->
      DeleteConfirmationDialog(
        isOpen = true,
        onDismiss = { playlistToDelete = null },
        onConfirm = {
          playlistToDelete = null
          scope.launch {
            playlistViewModel.deletePlaylist(target)
            playlistViewModel.refresh()
            recentsViewModel.refresh()
          }
        },
        itemCount = 1,
        itemType = "playlist",
        itemNames = listOf(target.name),
      )
    }
  }
}

/** The user's avatar, or the account glyph when none is set. Shared with the navigation pill and header. */
@Composable
internal fun ProfileAvatar(
  size: Dp,
  tint: Color,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
) {
  val path by koinInject<AppearancePreferences>().profileImagePath.collectAsState()
  val avatar = rememberProfileAvatar(path)
  if (avatar != null) {
    Image(
      bitmap = avatar,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = modifier.size(size).clip(CircleShape),
    )
  } else if (containerColor != null) {
    Surface(
      shape = CircleShape,
      color = containerColor,
      modifier = modifier.size(size),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          Icons.RoundedFilled.Person,
          contentDescription = contentDescription,
          tint = tint,
          modifier = Modifier.size(size * 0.54f),
        )
      }
    }
  } else {
    Icon(
      Icons.RoundedFilled.AccountCircle,
      contentDescription = contentDescription,
      tint = tint,
      modifier = modifier.size(size),
    )
  }
}

@Composable
private fun rememberProfileAvatar(path: String): ImageBitmap? {
  val avatar by produceState<ImageBitmap?>(initialValue = null, path) {
    value =
      if (path.isBlank()) {
        null
      } else {
        withContext(Dispatchers.IO) {
          runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
      }
  }
  return avatar
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileOverviewCard(
  name: String,
  recentCount: Int,
  playlistCount: Int,
  snapshotCount: Int,
  showNetwork: Boolean,
  collapseProgress: Float,
  onEditProfile: () -> Unit,
  onRecents: () -> Unit,
  onPlaylists: () -> Unit,
  onSnapshots: () -> Unit,
  onNetwork: () -> Unit,
) {
  val configuration = LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  val editLabel = stringResource(R.string.profile_edit)
  val totalItems = recentCount + playlistCount + snapshotCount
  val avatarScale = 1f - 0.25f * collapseProgress
  val avatarAlpha = (1f - collapseProgress * 1.5f).coerceIn(0f, 1f)

  val horizontalPadding = if (isTablet) 24.dp else 16.dp

  Column(
    modifier = Modifier.fillMaxWidth().padding(start = horizontalPadding, end = horizontalPadding, top = 2.dp, bottom = 0.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 0.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        contentAlignment = Alignment.BottomEnd,
        modifier =
          Modifier.graphicsLayer {
            scaleX = avatarScale
            scaleY = avatarScale
            alpha = avatarAlpha
          },
      ) {
        ProfileAvatar(
          size = if (isTablet) 116.dp else 96.dp,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentDescription = null,
        )
        Surface(
          onClick = onEditProfile,
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          modifier =
            Modifier
              .size(48.dp)
              .tvFocusHighlight(CircleShape),
          shadowElevation = 3.dp,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.RoundedFilled.Edit,
              contentDescription = editLabel,
              modifier = Modifier.size(if (isTablet) 18.dp else 16.dp),
            )
          }
        }
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = name.ifBlank { stringResource(R.string.ui_profile) },
          modifier = Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleLargeEmphasized,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Text(
            text = if (totalItems > 0) "$totalItems items in hub" else stringResource(R.string.ui_profile),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
          )
        }
      }
    }

    // Shortcut Tiles
    if (isTablet || (isLandscape && configuration.screenWidthDp >= 720)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        ProfileShortcutTile(
          icon = Icons.RoundedFilled.History,
          label = stringResource(R.string.ui_recents),
          count = recentCount,
          containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          contentColor = MaterialTheme.colorScheme.primary,
          onClick = onRecents,
          modifier = Modifier.weight(1f),
        )
        ProfileShortcutTile(
          icon = Icons.RoundedFilled.Subscriptions,
          label = stringResource(R.string.ui_playlists),
          count = playlistCount,
          containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          contentColor = MaterialTheme.colorScheme.secondary,
          onClick = onPlaylists,
          modifier = Modifier.weight(1f),
        )
        ProfileShortcutTile(
          icon = Icons.RoundedFilled.Image,
          label = stringResource(R.string.ui_snapshots),
          count = snapshotCount,
          containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          contentColor = MaterialTheme.colorScheme.tertiary,
          onClick = onSnapshots,
          modifier = Modifier.weight(1f),
        )
        if (showNetwork) {
          ProfileShortcutTile(
            icon = Icons.RoundedFilled.BringYourOwnIp,
            label = stringResource(R.string.ui_network),
            count = null,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onNetwork,
            modifier = Modifier.weight(1f),
          )
        }
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ProfileShortcutTile(
            icon = Icons.RoundedFilled.History,
            label = stringResource(R.string.ui_recents),
            count = recentCount,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.primary,
            onClick = onRecents,
            modifier = Modifier.weight(1f),
          )
          ProfileShortcutTile(
            icon = Icons.RoundedFilled.Subscriptions,
            label = stringResource(R.string.ui_playlists),
            count = playlistCount,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.secondary,
            onClick = onPlaylists,
            modifier = Modifier.weight(1f),
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ProfileShortcutTile(
            icon = Icons.RoundedFilled.Image,
            label = stringResource(R.string.ui_snapshots),
            count = snapshotCount,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.tertiary,
            onClick = onSnapshots,
            modifier = Modifier.weight(1f),
          )
          if (showNetwork) {
            ProfileShortcutTile(
              icon = Icons.RoundedFilled.BringYourOwnIp,
              label = stringResource(R.string.ui_network),
              count = null,
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              contentColor = MaterialTheme.colorScheme.onSurface,
              onClick = onNetwork,
              modifier = Modifier.weight(1f),
            )
          } else {
            Spacer(Modifier.weight(1f))
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileShortcutTile(
  icon: AppIcon,
  label: String,
  count: Int?,
  containerColor: Color,
  contentColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    shape = MaterialTheme.shapes.large,
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    modifier =
      modifier
        .heightIn(min = 64.dp)
        .tvFocusHighlight(MaterialTheme.shapes.large),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = contentColor.copy(alpha = 0.12f),
        contentColor = contentColor,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp),
      ) {
        if (count != null) {
          Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Text(
          text = label,
          style = if (count == null) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(
        Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SectionHeader(
  title: String,
  count: Int? = null,
  onViewAll: () -> Unit,
) {
  val configuration = LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = if (isTablet) 24.dp else 16.dp, end = if (isTablet) 16.dp else 8.dp, top = 12.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f),
    ) {
      Text(
        text = title,
        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (count != null && count > 0) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }
    }
    TextButton(
      onClick = onViewAll,
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
      shape = CircleShape,
    ) {
      Text(
        stringResource(R.string.profile_view_all),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.width(2.dp))
      Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
    }
  }
}

@Composable
private fun ShelfEmptyCard(
  icon: AppIcon,
  title: String,
  message: String,
) {
  val configuration = LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  Surface(
    shape = AppShapeScale.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 24.dp else 16.dp, vertical = 2.dp),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
      }
      Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.weight(1f),
      ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/** 16:9 frame for video or 1:1 frame for audio plus a fixed caption. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
  title: String,
  subtitle: String?,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  isAudio: Boolean = false,
  frame: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
  val cardWidth = if (isAudio) SHELF_AUDIO_CARD_WIDTH else SHELF_VIDEO_CARD_WIDTH
  val cardShape = if (isAudio) RoundedCornerShape(16.dp) else RoundedCornerShape(14.dp)
  val aspectRatio = if (isAudio) 1f else (16f / 9f)

  Column(
    modifier =
      Modifier
        .width(cardWidth)
        .tvFocusHighlight(cardShape)
        .clip(cardShape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(aspectRatio)
          .clip(cardShape)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
      content = frame,
    )
    Column(
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
      verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (subtitle == null) 2 else 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun RecentVideoCard(
  video: Video,
  progress: Float?,
  showThumbnail: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val isAudio = video.isAudio
  val cardWidth = if (isAudio) SHELF_AUDIO_CARD_WIDTH else SHELF_VIDEO_CARD_WIDTH
  val widthPx = with(density) { cardWidth.roundToPx() }
  val heightPx = if (isAudio) widthPx else (widthPx * 9 / 16)

  var thumbnail by remember(video.path, showThumbnail) {
    mutableStateOf(if (showThumbnail) thumbnailRepository.peekThumbnailFromMemory(video, widthPx, heightPx) else null)
  }
  LaunchedEffect(video.path, showThumbnail) {
    if (showThumbnail && thumbnail == null) {
      thumbnail = thumbnailRepository.getThumbnail(video, widthPx, heightPx)
    }
  }

  ShelfCard(
    title = video.displayName,
    subtitle = null,
    onClick = onClick,
    onLongClick = onLongClick,
    isAudio = isAudio,
  ) {
    val bitmap = thumbnail
    if (bitmap != null) {
      Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          if (isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(if (isAudio) 36.dp else 32.dp),
        )
      }
    }

    if (isAudio) {
      Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.65f),
        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            Icons.RoundedFilled.Audiotrack,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
          )
        }
      }
    }

    if (video.durationFormatted.isNotBlank() && video.durationFormatted != "--:--") {
      FrameBadge(video.durationFormatted, Modifier.align(Alignment.BottomEnd))
    }

    if (progress != null && progress > 0f) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Black.copy(alpha = 0.35f),
      )
    }
  }
}

@Composable
private fun SnapshotShelfCard(
  capture: FrameCapture,
  thumbnail: Bitmap?,
  onClick: () -> Unit,
) {
  ShelfCard(title = capture.videoTitle, subtitle = null, onClick = onClick, isAudio = false) {
    if (thumbnail != null) {
      Image(thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(Icons.RoundedFilled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(36.dp))
    }
    FrameBadge(capture.formattedPosition, Modifier.align(Alignment.BottomEnd))
  }
}

@Composable
private fun FrameBadge(
  text: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = Color.Black.copy(alpha = 0.75f),
    contentColor = Color.White,
    modifier = modifier.padding(6.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentVideoActionsSheet(
  video: Video,
  onDismiss: () -> Unit,
  onPlay: () -> Unit,
  onShare: () -> Unit,
  onRemove: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
      Text(
        text = video.displayName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
      SheetAction(Icons.RoundedFilled.PlayArrow, stringResource(R.string.ui_play), MaterialTheme.colorScheme.onSurface, onPlay)
      if (!video.path.contains("://")) {
        SheetAction(Icons.RoundedFilled.Share, stringResource(R.string.generic_share), MaterialTheme.colorScheme.onSurface, onShare)
      }
      SheetAction(Icons.RoundedFilled.Delete, stringResource(R.string.profile_remove_from_history), MaterialTheme.colorScheme.error, onRemove)
    }
  }
}

@Composable
private fun SheetAction(
  icon: AppIcon,
  label: String,
  tint: Color,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(AppShapeScale.medium)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditDialog(
  initialName: String,
  hasPhoto: Boolean,
  onDismiss: () -> Unit,
  onSave: (name: String, newPhoto: Bitmap?, removePhoto: Boolean) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val currentPath by koinInject<AppearancePreferences>().profileImagePath.collectAsState()
  val currentAvatar = rememberProfileAvatar(currentPath)
  var name by rememberSaveable { mutableStateOf(initialName) }
  var pendingPhoto by remember { mutableStateOf<Bitmap?>(null) }
  var rawCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var removePhoto by remember { mutableStateOf(false) }
  val pendingImage = remember(pendingPhoto) { pendingPhoto?.asImageBitmap() }
  val preview = pendingImage ?: currentAvatar.takeUnless { removePhoto }
  val showsPhoto = pendingPhoto != null || (hasPhoto && !removePhoto)

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      if (uri != null) {
        scope.launch {
          val decoded = withContext(Dispatchers.IO) { decodeRawProfileAvatar(context, uri) }
          if (decoded != null) {
            rawCropBitmap = decoded
          }
        }
      }
    }

  if (rawCropBitmap != null) {
    ProfileAvatarCropDialog(
      sourceBitmap = rawCropBitmap!!,
      onDismiss = { rawCropBitmap = null },
      onCropped = { cropped ->
        rawCropBitmap = null
        pendingPhoto = cropped
        removePhoto = false
      },
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.profile_edit)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Surface(
          onClick = { picker.launch("image/*") },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(96.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (preview != null) {
              Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
              Icon(
                Icons.RoundedFilled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
              )
            }
          }
        }
        // Three buttons don't fit one row in the dialog width once a photo exists; a plain Row
        // squeezed the last one into a thin vertical pill. FlowRow wraps it onto its own line.
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedButton(onClick = { picker.launch("image/*") }) {
            Text(stringResource(R.string.profile_choose_photo), maxLines = 1)
          }
          if (showsPhoto) {
            OutlinedButton(
              onClick = {
                val toCrop = pendingPhoto ?: currentPath.takeIf { it.isNotBlank() }?.let {
                  BitmapFactory.decodeFile(it)
                }
                if (toCrop != null) {
                  rawCropBitmap = toCrop
                }
              },
            ) {
              Text(stringResource(R.string.clip_crop), maxLines = 1)
            }
            OutlinedButton(
              onClick = {
                pendingPhoto = null
                removePhoto = true
              },
              colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
              ),
              border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
              ),
            ) {
              Text(stringResource(R.string.profile_remove_photo), maxLines = 1)
            }
          }
        }
        OutlinedTextField(
          value = name,
          onValueChange = { name = it.take(40) },
          label = { Text(stringResource(R.string.profile_display_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, pendingPhoto, removePhoto) }) {
        Text(stringResource(R.string.ui_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

@Composable
private fun ProfileAvatarCropDialog(
  sourceBitmap: Bitmap,
  onDismiss: () -> Unit,
  onCropped: (Bitmap) -> Unit,
) {
  val configuration = LocalConfiguration.current
  val density = LocalDensity.current
  val isTablet = configuration.smallestScreenWidthDp >= 600

  val cropBoxDp = minOf(configuration.screenWidthDp.dp - 64.dp, if (isTablet) 360.dp else 280.dp)
  val cropBoxPx = with(density) { cropBoxDp.toPx() }

  var rotation by remember { mutableStateOf(0) }
  var userScale by remember { mutableStateOf(1f) }
  var userOffset by remember { mutableStateOf(Offset.Zero) }

  val rotatedBitmap = remember(sourceBitmap, rotation) {
    if (rotation % 360 != 0) {
      val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
      Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
    } else {
      sourceBitmap
    }
  }

  val rotatedImageBitmap = remember(rotatedBitmap) { rotatedBitmap.asImageBitmap() }

  val baseScale = remember(rotatedImageBitmap, cropBoxPx) {
    maxOf(cropBoxPx / rotatedImageBitmap.width, cropBoxPx / rotatedImageBitmap.height)
  }

  // Calculate clamp boundaries so the image always completely covers the crop box
  val maxPanX = remember(userScale, baseScale, rotatedImageBitmap.width, cropBoxPx) {
    ((rotatedImageBitmap.width * baseScale * userScale - cropBoxPx) / 2f).coerceAtLeast(0f)
  }
  val maxPanY = remember(userScale, baseScale, rotatedImageBitmap.height, cropBoxPx) {
    ((rotatedImageBitmap.height * baseScale * userScale - cropBoxPx) / 2f).coerceAtLeast(0f)
  }

  // Keep offset clamped when zoom changes
  LaunchedEffect(maxPanX, maxPanY) {
    userOffset = Offset(
      userOffset.x.coerceIn(-maxPanX, maxPanX),
      userOffset.y.coerceIn(-maxPanY, maxPanY),
    )
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier =
        Modifier
          .fillMaxWidth(if (isTablet) 0.65f else 0.94f)
          .clip(AppShapeScale.large),
      shape = AppShapeScale.large,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.clip_crop),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.generic_cancel))
          }
        }

        // Viewport
        Box(
          modifier =
            Modifier
              .size(cropBoxDp)
              .clip(RoundedCornerShape(12.dp))
              .background(Color.Black)
              .pointerInput(rotatedImageBitmap, cropBoxPx) {
                detectTransformGestures { _, pan, zoom, _ ->
                  userScale = (userScale * zoom).coerceIn(1f, 5f)
                  val currentMaxPanX = ((rotatedImageBitmap.width * baseScale * userScale - cropBoxPx) / 2f).coerceAtLeast(0f)
                  val currentMaxPanY = ((rotatedImageBitmap.height * baseScale * userScale - cropBoxPx) / 2f).coerceAtLeast(0f)
                  userOffset = Offset(
                    (userOffset.x + pan.x).coerceIn(-currentMaxPanX, currentMaxPanX),
                    (userOffset.y + pan.y).coerceIn(-currentMaxPanY, currentMaxPanY),
                  )
                }
              },
          contentAlignment = Alignment.Center,
        ) {
          Image(
            bitmap = rotatedImageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
              Modifier
                .size(
                  width = with(density) { (rotatedImageBitmap.width * baseScale).toDp() },
                  height = with(density) { (rotatedImageBitmap.height * baseScale).toDp() },
                )
                .graphicsLayer {
                  scaleX = userScale
                  scaleY = userScale
                  translationX = userOffset.x
                  translationY = userOffset.y
                },
          )

          // Circular crop cutout mask overlay
          Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val radius = minOf(width, height) / 2f

            val path = Path().apply {
              fillType = PathFillType.EvenOdd
              addRect(Rect(0f, 0f, width, height))
              addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
            }
            drawPath(path, color = Color.Black.copy(alpha = 0.60f))

            drawCircle(
              color = Color.White.copy(alpha = 0.85f),
              radius = radius - 1f,
              center = center,
              style = Stroke(width = 2.dp.toPx()),
            )
          }
        }

        // Toolbar: Rotate Left, Rotate Right & Reset
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilledTonalButton(
            onClick = {
              rotation = (rotation - 90 + 360) % 360
              userScale = 1f
              userOffset = Offset.Zero
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          ) {
            Icon(Icons.RoundedFilled.RotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Left", style = MaterialTheme.typography.labelMedium)
          }

          FilledTonalButton(
            onClick = {
              rotation = (rotation + 90) % 360
              userScale = 1f
              userOffset = Offset.Zero
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          ) {
            Icon(Icons.RoundedFilled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Right", style = MaterialTheme.typography.labelMedium)
          }

          FilledTonalButton(
            onClick = {
              rotation = 0
              userScale = 1f
              userOffset = Offset.Zero
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          ) {
            Icon(Icons.RoundedFilled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.generic_reset), style = MaterialTheme.typography.labelMedium)
          }
        }

        // Action Buttons
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.generic_cancel))
          }
          Button(
            onClick = {
              val cropped = performCrop(
                bitmap = sourceBitmap,
                rotation = rotation,
                userScale = userScale,
                userOffset = userOffset,
                viewportSizePx = cropBoxPx,
              )
              onCropped(cropped)
            },
          ) {
            Text(stringResource(R.string.clip_crop))
          }
        }
      }
    }
  }
}

private fun performCrop(
  bitmap: Bitmap,
  rotation: Int,
  userScale: Float,
  userOffset: Offset,
  viewportSizePx: Float,
  targetSizePx: Int = AVATAR_SIZE_PX,
): Bitmap {
  val rotated = if (rotation % 360 != 0) {
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  } else {
    bitmap
  }

  val bw = rotated.width.toFloat()
  val bh = rotated.height.toFloat()

  val baseScale = maxOf(viewportSizePx / bw, viewportSizePx / bh)
  val totalScale = baseScale * userScale

  val cropSizeInImage = (viewportSizePx / totalScale).coerceAtMost(minOf(bw, bh))
  val cropCenterX = bw / 2f - userOffset.x / totalScale
  val cropCenterY = bh / 2f - userOffset.y / totalScale

  val srcX = (cropCenterX - cropSizeInImage / 2f).toInt().coerceIn(0, (bw - cropSizeInImage).toInt().coerceAtLeast(0))
  val srcY = (cropCenterY - cropSizeInImage / 2f).toInt().coerceIn(0, (bh - cropSizeInImage).toInt().coerceAtLeast(0))
  val srcW = cropSizeInImage.toInt().coerceAtMost(rotated.width - srcX)
  val srcH = cropSizeInImage.toInt().coerceAtMost(rotated.height - srcY)
  val cropSide = minOf(srcW, srcH).coerceAtLeast(1)

  val scaleMatrix = Matrix().apply {
    val scale = targetSizePx.toFloat() / cropSide
    postScale(scale, scale)
  }

  return Bitmap.createBitmap(rotated, srcX, srcY, cropSide, cropSide, scaleMatrix, true)
}

/** Decodes [uri] into an upright raw bitmap for cropping. */
private fun decodeRawProfileAvatar(
  context: Context,
  uri: Uri,
): Bitmap? =
  runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sampleSize * 2) >= 2048) sampleSize *= 2
    val decoded =
      resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
      } ?: return null

    val rotation =
      resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 270f
          else -> 0f
        }
      } ?: 0f

    if (rotation != 0f) {
      val matrix = Matrix().apply { postRotate(rotation) }
      Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } else {
      decoded
    }
  }.getOrNull()

/** Replaces the stored avatar with [bitmap] (or clears it when null) and returns the new path. */
private fun storeProfileAvatar(
  context: Context,
  bitmap: Bitmap?,
): String {
  context.filesDir.listFiles { file -> file.name.startsWith(AVATAR_FILE_PREFIX) }?.forEach(File::delete)
  if (bitmap == null) return ""
  // A fresh name per save changes the preference value, so every avatar observer reloads.
  val file = File(context.filesDir, "$AVATAR_FILE_PREFIX${System.currentTimeMillis()}.png")
  file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  return file.absolutePath
}
