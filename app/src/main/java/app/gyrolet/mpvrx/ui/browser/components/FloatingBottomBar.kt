/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

private data class BarLayoutParams(
  val buttonSize: androidx.compose.ui.unit.Dp,
  val iconSize: androidx.compose.ui.unit.Dp,
  val spacing: androidx.compose.ui.unit.Dp,
  val rowPaddingHorizontal: androidx.compose.ui.unit.Dp,
  val rowPaddingVertical: androidx.compose.ui.unit.Dp,
  val surfacePaddingHorizontal: androidx.compose.ui.unit.Dp,
  val surfacePaddingVertical: androidx.compose.ui.unit.Dp,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserBottomBar(
  isSelectionMode: Boolean,
  onCopyClick: () -> Unit,
  onMoveClick: () -> Unit,
  onDownscaleClick: () -> Unit = {},
  onRenameClick: () -> Unit,
  onDeleteClick: () -> Unit,
  onAddToPlaylistClick: () -> Unit,
  modifier: Modifier = Modifier,
  showCopy: Boolean = true,
  showMove: Boolean = true,
  showDownscale: Boolean = false,
  showRename: Boolean = true,
  showDelete: Boolean = true,
  showAddToPlaylist: Boolean = true,
) {
  val configuration = LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  var lastShowCopy by remember { mutableStateOf(showCopy) }
  var lastShowMove by remember { mutableStateOf(showMove) }
  var lastShowDownscale by remember { mutableStateOf(showDownscale) }
  var lastShowRename by remember { mutableStateOf(showRename) }
  var lastShowDelete by remember { mutableStateOf(showDelete) }
  var lastShowAddToPlaylist by remember { mutableStateOf(showAddToPlaylist) }

  if (isSelectionMode) {
    lastShowCopy = showCopy
    lastShowMove = showMove
    lastShowDownscale = showDownscale
    lastShowRename = showRename
    lastShowDelete = showDelete
    lastShowAddToPlaylist = showAddToPlaylist
  }

  val effectiveShowCopy = if (isSelectionMode) showCopy else lastShowCopy
  val effectiveShowMove = if (isSelectionMode) showMove else lastShowMove
  val effectiveShowDownscale = if (isSelectionMode) showDownscale else lastShowDownscale
  val effectiveShowRename = if (isSelectionMode) showRename else lastShowRename
  val effectiveShowDelete = if (isSelectionMode) showDelete else lastShowDelete
  val effectiveShowAddToPlaylist = if (isSelectionMode) showAddToPlaylist else lastShowAddToPlaylist

  val stackedModifier =
    if (NavigationBarState.isNavBarVisible) {
      modifier.padding(bottom = NavigationBarState.navigationBarClearance)
    } else {
      modifier
    }

  AnimatedVisibility(
    visible = isSelectionMode,
    modifier = stackedModifier,
    enter =
      androidx.compose.animation.slideInVertically(
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Spatial.ExpressiveDp.dampingRatio,
            stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Spatial.ExpressiveDp.stiffness,
          ),
        initialOffsetY = { fullHeight -> fullHeight * 2 },
      ) + fadeIn(),
    exit =
      androidx.compose.animation.slideOutVertically(
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
          ),
        targetOffsetY = { fullHeight -> fullHeight * 2 },
      ) + fadeOut(),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
      val availableWidth = maxWidth
      val visibleCount =
        listOf(
          effectiveShowCopy,
          effectiveShowMove,
          effectiveShowDownscale,
          effectiveShowRename,
          effectiveShowAddToPlaylist,
          effectiveShowDelete,
        ).count { it }

      val layoutParams =
        when {
          visibleCount <= 1 -> {
            val buttonSize = if (isTablet) 64.dp else 56.dp
            val iconSize = if (isTablet) 32.dp else 28.dp
            val surfPadHoriz = if (availableWidth < 360.dp) 12.dp else 24.dp
            val surfPadVert =
              if (isTablet) {
                16.dp
              } else if (isLandscape) {
                6.dp
              } else {
                12.dp
              }
            val rowPadHoriz = if (availableWidth < 360.dp) 8.dp else 16.dp
            val rowPadVert =
              if (isTablet) {
                8.dp
              } else if (isLandscape) {
                4.dp
              } else {
                8.dp
              }
            BarLayoutParams(
              buttonSize = buttonSize,
              iconSize = iconSize,
              spacing = 0.dp,
              rowPaddingHorizontal = rowPadHoriz,
              rowPaddingVertical = rowPadVert,
              surfacePaddingHorizontal = surfPadHoriz,
              surfacePaddingVertical = surfPadVert,
            )
          }
          isTablet -> {
            val options =
              listOf(
                BarLayoutParams(64.dp, 32.dp, 24.dp, 20.dp, 8.dp, 32.dp, 16.dp),
                BarLayoutParams(56.dp, 28.dp, 16.dp, 16.dp, 6.dp, 24.dp, 12.dp),
                BarLayoutParams(48.dp, 24.dp, 12.dp, 12.dp, 6.dp, 16.dp, 10.dp),
              )
            options.firstOrNull { opt ->
              val totalWidth =
                (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) +
                  (opt.surfacePaddingHorizontal * 2)
              totalWidth <= availableWidth
            } ?: options.last()
          }
          isLandscape -> {
            val options =
              listOf(
                BarLayoutParams(56.dp, 28.dp, 12.dp, 10.dp, 4.dp, 16.dp, 6.dp),
                BarLayoutParams(48.dp, 24.dp, 10.dp, 8.dp, 4.dp, 12.dp, 6.dp),
                BarLayoutParams(42.dp, 22.dp, 8.dp, 6.dp, 2.dp, 8.dp, 4.dp),
                BarLayoutParams(36.dp, 18.dp, 6.dp, 4.dp, 2.dp, 6.dp, 4.dp),
              )
            options.firstOrNull { opt ->
              val totalWidth =
                (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) +
                  (opt.surfacePaddingHorizontal * 2)
              totalWidth <= availableWidth
            } ?: options.last()
          }
          else -> {
            val options =
              listOf(
                BarLayoutParams(56.dp, 28.dp, 12.dp, 10.dp, 8.dp, 16.dp, 12.dp),
                BarLayoutParams(48.dp, 24.dp, 10.dp, 8.dp, 6.dp, 12.dp, 10.dp),
                BarLayoutParams(42.dp, 22.dp, 8.dp, 6.dp, 4.dp, 8.dp, 8.dp),
                BarLayoutParams(36.dp, 18.dp, 6.dp, 4.dp, 4.dp, 6.dp, 6.dp),
              )
            options.firstOrNull { opt ->
              val totalWidth =
                (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) +
                  (opt.surfacePaddingHorizontal * 2)
              totalWidth <= availableWidth
            } ?: options.last()
          }
        }

      Surface(
        modifier =
          Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .align(Alignment.BottomCenter)
            .padding(
              horizontal = layoutParams.surfacePaddingHorizontal,
              vertical = layoutParams.surfacePaddingVertical,
            ),
        shape = RoundedCornerShape(percent = 100),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
      ) {
        Row(
          modifier =
            Modifier.padding(
              horizontal = layoutParams.rowPaddingHorizontal,
              vertical = layoutParams.rowPaddingVertical,
            ),
          horizontalArrangement = Arrangement.spacedBy(layoutParams.spacing),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BrowserBottomBarButton(effectiveShowCopy, onCopyClick, Icons.RoundedFilled.ContentCopy, "Copy", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(effectiveShowMove, onMoveClick, Icons.RoundedFilled.DriveFileMove, "Move", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(effectiveShowDownscale, onDownscaleClick, Icons.RoundedFilled.FitScreen, "Compressor", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(effectiveShowRename, onRenameClick, Icons.RoundedFilled.DriveFileRenameOutline, "Rename", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(effectiveShowAddToPlaylist, onAddToPlaylistClick, Icons.RoundedFilled.PlaylistAdd, "Add to Playlist", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(
            effectiveShowDelete,
            onDeleteClick,
            Icons.RoundedFilled.Delete,
            "Delete",
            layoutParams.buttonSize,
            layoutParams.iconSize,
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun BrowserBottomBarButton(
  show: Boolean,
  onClick: () -> Unit,
  icon: AppIcon,
  contentDescription: String,
  buttonSize: androidx.compose.ui.unit.Dp,
  iconSize: androidx.compose.ui.unit.Dp,
  tint: Color = MaterialTheme.colorScheme.primary,
) {
  if (show) {
    IconButton(
      onClick = onClick,
      modifier = Modifier.size(buttonSize),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(iconSize),
        tint = tint,
      )
    }
  }
}
