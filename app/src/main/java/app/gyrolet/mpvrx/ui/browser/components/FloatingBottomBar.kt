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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.LiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.LiquidGlassEditingBarSurface
import app.gyrolet.mpvrx.presentation.components.rememberLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import org.koin.compose.koinInject

@Composable
internal fun rememberBrowserBottomBarBackdrop(): LiquidGlassBackdrop? {
  val preferences = koinInject<AppearancePreferences>()
  val enabled by preferences.liquidGlassEnabled.collectAsState()
  return rememberLiquidGlassBackdrop().takeIf { enabled }
}

private data class BarLayoutParams(
  val buttonSize: androidx.compose.ui.unit.Dp,
  val iconSize: androidx.compose.ui.unit.Dp,
  val spacing: androidx.compose.ui.unit.Dp,
  val rowPaddingHorizontal: androidx.compose.ui.unit.Dp,
  val rowPaddingVertical: androidx.compose.ui.unit.Dp,
  val surfacePaddingHorizontal: androidx.compose.ui.unit.Dp,
  val surfacePaddingVertical: androidx.compose.ui.unit.Dp,
)

/** A screen-specific action rendered before Delete in [BrowserBottomBar]. */
data class BrowserBottomBarAction(
  val icon: AppIcon,
  val label: String,
  val onClick: () -> Unit,
  val tint: Color? = null,
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
  onPlayNextClick: (() -> Unit)? = null,
  onAddToQueueClick: (() -> Unit)? = null,
  onPinClick: (() -> Unit)? = null,
  unpinSelected: Boolean = false,
  pinLabelRes: Int = app.gyrolet.mpvrx.R.string.ui_pin_folders,
  unpinLabelRes: Int = app.gyrolet.mpvrx.R.string.ui_unpin_folders,
  modifier: Modifier = Modifier,
  showCopy: Boolean = true,
  showMove: Boolean = true,
  showDownscale: Boolean = false,
  showRename: Boolean = true,
  showDelete: Boolean = true,
  showAddToPlaylist: Boolean = true,
  backdrop: LiquidGlassBackdrop? = null,
  extraActions: List<BrowserBottomBarAction> = emptyList(),
  deleteIcon: AppIcon = Icons.RoundedFilled.Delete,
  deleteLabel: String = stringResource(R.string.delete),
) {
  val configuration = LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  val reducedMotion = AppMotion.shouldReduceMotion()
  val density = LocalDensity.current
  val haptics =
    app.gyrolet.mpvrx.ui.utils
      .rememberAppHaptics()
  val entranceOffset = with(LocalDensity.current) { 12.dp.roundToPx() }

  var lastShowCopy by remember { mutableStateOf(showCopy) }
  var lastShowMove by remember { mutableStateOf(showMove) }
  var lastShowDownscale by remember { mutableStateOf(showDownscale) }
  var lastShowRename by remember { mutableStateOf(showRename) }
  var lastShowDelete by remember { mutableStateOf(showDelete) }
  var lastShowAddToPlaylist by remember { mutableStateOf(showAddToPlaylist) }
  var lastShowPlayNext by remember { mutableStateOf(onPlayNextClick != null) }
  var lastShowAddToQueue by remember { mutableStateOf(onAddToQueueClick != null) }
  var lastShowPin by remember { mutableStateOf(onPinClick != null) }
  var lastUnpinSelected by remember { mutableStateOf(unpinSelected) }
  var lastExtraActions by remember { mutableStateOf(extraActions) }

  if (isSelectionMode) {
    lastShowCopy = showCopy
    lastShowMove = showMove
    lastShowDownscale = showDownscale
    lastShowRename = showRename
    lastShowDelete = showDelete
    lastShowAddToPlaylist = showAddToPlaylist
    lastShowPlayNext = onPlayNextClick != null
    lastShowAddToQueue = onAddToQueueClick != null
    lastShowPin = onPinClick != null
    lastUnpinSelected = unpinSelected
    lastExtraActions = extraActions
  }

  val effectiveShowCopy = if (isSelectionMode) showCopy else lastShowCopy
  val effectiveShowMove = if (isSelectionMode) showMove else lastShowMove
  val effectiveShowDownscale = if (isSelectionMode) showDownscale else lastShowDownscale
  val effectiveShowRename = if (isSelectionMode) showRename else lastShowRename
  val effectiveShowDelete = if (isSelectionMode) showDelete else lastShowDelete
  val effectiveShowAddToPlaylist = if (isSelectionMode) showAddToPlaylist else lastShowAddToPlaylist
  val effectiveShowPlayNext = if (isSelectionMode) onPlayNextClick != null else lastShowPlayNext
  val effectiveShowAddToQueue = if (isSelectionMode) onAddToQueueClick != null else lastShowAddToQueue
  val effectiveShowPin = if (isSelectionMode) onPinClick != null else lastShowPin
  val effectiveUnpinSelected = if (isSelectionMode) unpinSelected else lastUnpinSelected
  val effectiveExtraActions = if (isSelectionMode) extraActions else lastExtraActions

  AnimatedVisibility(
    visible = isSelectionMode,
    modifier = modifier,
    enter =
      if (reducedMotion) {
        EnterTransition.None
      } else {
        androidx.compose.animation.slideInVertically(
          animationSpec =
            androidx.compose.animation.core.spring(
              dampingRatio = AppMotion.Spatial.ExpressiveDp.dampingRatio,
              stiffness = AppMotion.Spatial.ExpressiveDp.stiffness,
            ),
          initialOffsetY = { entranceOffset },
        ) +
          fadeIn(
            androidx.compose.animation.core
              .tween(180),
          )
      },
    exit =
      if (reducedMotion) {
        ExitTransition.None
      } else {
        androidx.compose.animation.slideOutVertically(
          animationSpec =
            androidx.compose.animation.core.spring(
              dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
              stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
            ),
          targetOffsetY = { entranceOffset },
        ) +
          fadeOut(
            androidx.compose.animation.core
              .tween(120),
          )
      },
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
      val availableWidth = maxWidth
      val visibleCount =
        listOf(
          effectiveShowCopy,
          effectiveShowMove,
          effectiveShowDownscale,
          effectiveShowRename,
          effectiveShowPlayNext,
          effectiveShowAddToQueue,
          effectiveShowPin,
          effectiveShowAddToPlaylist,
          effectiveShowDelete,
        ).count { it } + effectiveExtraActions.size

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
                BarLayoutParams(64.dp, 32.dp, 24.dp, 20.dp, 8.dp, 32.dp, 16.dp), // Large
                BarLayoutParams(56.dp, 28.dp, 16.dp, 16.dp, 6.dp, 24.dp, 12.dp), // Medium
                BarLayoutParams(48.dp, 24.dp, 12.dp, 12.dp, 6.dp, 16.dp, 10.dp), // Small
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
                BarLayoutParams(56.dp, 28.dp, 12.dp, 10.dp, 4.dp, 16.dp, 6.dp), // Large (Compact vertical)
                BarLayoutParams(48.dp, 24.dp, 10.dp, 8.dp, 4.dp, 12.dp, 6.dp), // Medium (Compact vertical)
                BarLayoutParams(48.dp, 24.dp, 4.dp, 4.dp, 2.dp, 4.dp, 2.dp),
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
                BarLayoutParams(56.dp, 28.dp, 12.dp, 10.dp, 8.dp, 16.dp, 12.dp), // Large
                BarLayoutParams(48.dp, 24.dp, 10.dp, 8.dp, 6.dp, 12.dp, 10.dp), // Medium
                BarLayoutParams(48.dp, 24.dp, 4.dp, 4.dp, 4.dp, 4.dp, 4.dp),
              )
            options.firstOrNull { opt ->
              val totalWidth =
                (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) +
                  (opt.surfacePaddingHorizontal * 2)
              totalWidth <= availableWidth
            } ?: options.last()
          }
        }

      Box(
        modifier =
          Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .align(Alignment.BottomCenter)
            .padding(
              start = layoutParams.surfacePaddingHorizontal,
              top = layoutParams.surfacePaddingVertical,
              end = layoutParams.surfacePaddingHorizontal,
              bottom = NavigationBarState.floatingBarSpacing,
            ),
      ) {
        LiquidGlassEditingBarSurface(
          backdrop = backdrop,
        ) {
          Row(
            modifier =
              Modifier
                .onSizeChanged { size ->
                  if (isSelectionMode) {
                    NavigationBarState.selectionBarHeight = with(density) { size.height.toDp() }
                  }
                }.horizontalScroll(rememberScrollState())
                .padding(
                  horizontal = layoutParams.rowPaddingHorizontal,
                  vertical = layoutParams.rowPaddingVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(layoutParams.spacing),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            BrowserBottomBarButton(
              effectiveShowCopy,
              onCopyClick,
              Icons.RoundedFilled.ContentCopy,
              stringResource(R.string.ui_copy),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowMove,
              onMoveClick,
              Icons.RoundedFilled.DriveFileMove,
              stringResource(R.string.snapshot_move),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowDownscale,
              onDownscaleClick,
              Icons.RoundedFilled.FitScreen,
              stringResource(R.string.ui_compressor),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowRename,
              onRenameClick,
              Icons.RoundedFilled.DriveFileRenameOutline,
              stringResource(R.string.rename),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowPlayNext,
              onPlayNextClick ?: {},
              Icons.RoundedFilled.SkipNext,
              stringResource(R.string.video_swipe_play_next),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowAddToQueue,
              onAddToQueueClick ?: {},
              Icons.RoundedFilled.QueueMusic,
              stringResource(R.string.video_swipe_add_queue),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowAddToPlaylist,
              onAddToPlaylistClick,
              Icons.RoundedFilled.PlaylistAdd,
              stringResource(R.string.ui_add_to_playlist),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            BrowserBottomBarButton(
              effectiveShowPin,
              {
                if (isSelectionMode) {
                  onPinClick?.let { action ->
                    action()
                    haptics.selection(!effectiveUnpinSelected)
                  }
                }
              },
              Icons.RoundedFilled.PushPin,
              androidx.compose.ui.res.stringResource(
                if (effectiveUnpinSelected) unpinLabelRes else pinLabelRes,
              ),
              layoutParams.buttonSize,
              layoutParams.iconSize,
            )
            effectiveExtraActions.forEach { action ->
              BrowserBottomBarButton(
                true,
                action.onClick,
                action.icon,
                action.label,
                layoutParams.buttonSize,
                layoutParams.iconSize,
                tint = action.tint ?: MaterialTheme.colorScheme.primary,
              )
            }
            BrowserBottomBarButton(
              effectiveShowDelete,
              onDeleteClick,
              deleteIcon,
              deleteLabel,
              layoutParams.buttonSize,
              layoutParams.iconSize,
              tint = MaterialTheme.colorScheme.error,
            )
          }
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
      modifier =
        Modifier
          .tvFocusHighlight(CircleShape, focusedScale = 1.06f)
          .size(buttonSize),
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
