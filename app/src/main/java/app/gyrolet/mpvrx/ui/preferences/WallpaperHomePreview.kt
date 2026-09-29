/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.ExpressivePillNavigationBar
import app.gyrolet.mpvrx.ui.browser.MainScreen
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.LocalAppWallpaperActive
import app.gyrolet.mpvrx.ui.theme.WallpaperImage
import app.gyrolet.mpvrx.ui.theme.WallpaperScaleMode
import app.gyrolet.mpvrx.ui.theme.rememberWallpaperScrimColor
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.utils.calculateResponsiveGridSpans
import org.koin.compose.koinInject

/**
 * Full-screen preview of the real home screen drawn over the wallpaper using the *unsaved* editor
 * values. It uses the app's own top bar, folder cards and pill navigation bar (with sample folders),
 * so it follows the same layout, tabs and card settings as the real home. Tap anywhere to close.
 */
@Composable
internal fun WallpaperHomePreviewDialog(
  bitmap: Bitmap?,
  zoom: Float,
  offsetX: Float,
  offsetY: Float,
  scaleMode: WallpaperScaleMode,
  blur: Float,
  alpha: Float,
  onDismiss: () -> Unit,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val showMusicTab by appearancePreferences.showMusicTab.collectAsState()
  val showRecentsTab by appearancePreferences.showRecentsTab.collectAsState()
  val showPlaylistsTab by appearancePreferences.showPlaylistsTab.collectAsState()
  val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
  val showJellyfinTab by appearancePreferences.showJellyfinTab.collectAsState()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  val layoutMode by browserPreferences.folderViewFolderLayoutMode.collectAsState()

  // Same tab order as MainScreen: Home is always first, the pill only shows with 2+ tabs.
  val navigationTabs =
    remember(showMusicTab, showRecentsTab, showPlaylistsTab, showNetworkTab, showJellyfinTab) {
      val tabs =
        buildList {
          add(MainScreen.MainTab.HOME)
          if (showMusicTab) add(MainScreen.MainTab.MUSIC)
          if (showRecentsTab) add(MainScreen.MainTab.RECENTS)
          if (showPlaylistsTab) add(MainScreen.MainTab.PLAYLISTS)
          if (showNetworkTab) add(MainScreen.MainTab.NETWORK)
          if (showJellyfinTab) add(MainScreen.MainTab.JELLYFIN)
        }
      tabs.takeIf { it.size > 1 }.orEmpty()
    }
  val contentBottomPadding = if (navigationTabs.isEmpty()) 0.dp else 88.dp
  val folders = remember { previewFolders() }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalAppWallpaperActive provides (bitmap != null)) {
      Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        if (bitmap != null) {
          WallpaperImage(
            bitmap = bitmap,
            zoom = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            scaleMode = scaleMode,
            blurRadius = blur,
            imageAlpha = alpha,
            modifier = Modifier.fillMaxSize(),
          )
          // Same scrim AppWallpaperHost puts over the wallpaper.
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(rememberWallpaperScrimColor(bitmap)),
          )
        }

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          containerColor = wallpaperAwareBackgroundColor(),
          topBar = {
            BrowserTopBar(
              title = stringResource(R.string.app_name),
              isInSelectionMode = false,
              selectedCount = 0,
              totalCount = folders.size,
              onCancelSelection = {},
              onSortClick = {},
              onSearchClick = {},
              onSettingsClick = {},
            )
          },
        ) { padding ->
          Box(modifier = Modifier.padding(padding)) {
            if (layoutMode == MediaLayoutMode.GRID) {
              BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val spansInfo = calculateResponsiveGridSpans(maxWidth = maxWidth)
                LazyVerticalGrid(
                  columns = GridCells.Fixed((spansInfo.spans / spansInfo.folderSpan).coerceAtLeast(1)),
                  modifier = Modifier.fillMaxSize(),
                  contentPadding =
                    PaddingValues(
                      start = 8.dp,
                      end = 8.dp,
                      top = 8.dp,
                      bottom = contentBottomPadding + 8.dp,
                    ),
                  horizontalArrangement = Arrangement.spacedBy(2.dp),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                  userScrollEnabled = false,
                ) {
                  items(folders, key = { it.bucketId }) { folder ->
                    FolderCard(
                      folder = folder,
                      onClick = {},
                      newVideoCount = PREVIEW_NEW_COUNTS[folder.bucketId] ?: 0,
                      isGridMode = true,
                    )
                  }
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = contentBottomPadding,
                  ),
                userScrollEnabled = false,
              ) {
                items(folders, key = { it.bucketId }) { folder ->
                  FolderCard(
                    folder = folder,
                    onClick = {},
                    newVideoCount = PREVIEW_NEW_COUNTS[folder.bucketId] ?: 0,
                    isGridMode = false,
                  )
                }
              }
            }
          }
        }

        if (showQuickPlayFab) {
          ToggleFloatingActionButton(
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = maxOf(contentBottomPadding, 16.dp)),
            checked = false,
            onCheckedChange = {},
          ) {
            Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = null)
          }
        }

        if (navigationTabs.isNotEmpty()) {
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
          ) {
            ExpressivePillNavigationBar(
              visibleTabs = navigationTabs,
              selectedTab = MainScreen.MainTab.HOME,
              onTabSelected = {},
              pagerState = null,
            )
          }
        }

        // Swallows every touch so the sample content can't be scrolled or tapped; any tap closes.
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
              ),
        )

        // Close chip
        Surface(
          shape = CircleShape,
          color = colors.inverseSurface.copy(alpha = 0.85f),
          modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 4.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.RoundedFilled.Close, contentDescription = null, tint = colors.inverseOnSurface, modifier = Modifier.size(16.dp))
            Text(
              text = stringResource(R.string.pref_appearance_custom_wallpaper_home_preview_close),
              style = MaterialTheme.typography.labelMedium,
              color = colors.inverseOnSurface,
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
      }
    }
  }
}

private val PREVIEW_NEW_COUNTS = mapOf("preview_camera" to 2, "preview_downloads" to 5)

private fun previewFolders(): List<VideoFolder> {
  val now = System.currentTimeMillis() / 1000L
  val day = 24L * 60L * 60L
  val mb = 1024L * 1024L
  val minute = 60_000L
  return listOf(
    VideoFolder("preview_camera", "Camera", "/storage/emulated/0/DCIM/Camera", 128, 3_200L * mb, 214 * minute, now - day),
    VideoFolder("preview_downloads", "Downloads", "/storage/emulated/0/Download", 42, 8_400L * mb, 356 * minute, now - 2 * day),
    VideoFolder("preview_movies", "Movies", "/storage/emulated/0/Movies", 17, 12_900L * mb, 1_120 * minute, now - 5 * day),
    VideoFolder("preview_recordings", "Screen recordings", "/storage/emulated/0/Movies/Screen recordings", 9, 640L * mb, 38 * minute, now - 9 * day),
    VideoFolder("preview_whatsapp", "WhatsApp Video", "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", 63, 1_900L * mb, 96 * minute, now - 14 * day),
    VideoFolder("preview_telegram", "Telegram", "/storage/emulated/0/Telegram/Telegram Video", 24, 2_300L * mb, 141 * minute, now - 21 * day),
  )
}
