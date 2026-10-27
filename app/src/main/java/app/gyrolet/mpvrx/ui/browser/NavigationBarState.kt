/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import com.kyant.backdrop.Backdrop

/**
 * Centralized state holder for browser navigation visibility.
 * Uses Compose state so screens recompose only when values actually change.
 */
object NavigationBarState {
  private var selectionOwner: Any? = null

  internal fun claimSelection(owner: Any, inSelectionMode: Boolean, onlyVideos: Boolean) {
    selectionOwner = owner
    updateSelectionState(inSelectionMode, onlyVideos)
  }

  internal fun releaseSelection(owner: Any) {
    if (selectionOwner == owner) {
      selectionOwner = null
      updateSelectionState(inSelectionMode = false)
    }
  }

  var isInSelectionMode: Boolean by mutableStateOf(false)
    private set

  var isDualPaneFolderSelected: Boolean by mutableStateOf(false)

  // Mini player coordination state (published by MiniPlayer/MainScreen so the
  // browser layout can adapt when the mini player is on screen).
  var isMiniPlayerVisible: Boolean by mutableStateOf(false)

  internal var miniPlayerConnectionEnabled: Boolean by mutableStateOf(false)
  internal var isMiniPlayerDocked: Boolean by mutableStateOf(false)
  internal var dockProgress: Float by mutableFloatStateOf(0f)
  internal var dockBackdrop: Backdrop? by mutableStateOf(null)

  // Includes both navigation styles, but not bars belonging to a covered screen.
  var isNavBarVisible: Boolean by mutableStateOf(false)

  var isDualPaneLayout: Boolean by mutableStateOf(false)
    internal set

  var isMiniPlayerBesideNavigation: Boolean by mutableStateOf(false)
    internal set

  var navbarLeftOffset: Dp by mutableStateOf(0.dp)

  var navbarWidth: Dp by mutableStateOf(320.dp)

  val floatingBarSpacing: Dp = 12.dp
  internal val connectedDockSpacing: Dp = 2.dp
  internal val connectedDockCorner: Dp = 4.dp
  internal val navigationPillMaxWidth: Dp = 400.dp
  internal val navigationPillMaxMargin: Dp = 58.dp
  internal val miniPlayerMinWidth: Dp = 320.dp

  // Heights exclude system insets and outer margins.
  var navigationPillHeight: Dp by mutableStateOf(68.dp)
    internal set

  var navigationBarBottomSpacing: Dp by mutableStateOf(floatingBarSpacing)
    internal set

  var selectionBarHeight: Dp by mutableStateOf(72.dp)
    internal set

  var miniPlayerHeight: Dp by mutableStateOf(64.dp)
    internal set

  val navigationBarClearance: Dp
    get() = navigationPillHeight + navigationBarBottomSpacing + floatingBarSpacing

  val selectionBarClearance: Dp
    get() = selectionBarHeight + floatingBarSpacing * 2

  val bottomBarClearance: Dp
    get() = when {
      isInSelectionMode -> selectionBarClearance
      isNavBarVisible -> navigationBarClearance
      else -> 0.dp
    }

  val miniPlayerBottomSpacing: Dp
    get() = when {
      isInSelectionMode -> selectionBarClearance
      isMiniPlayerDocked -> navigationPillHeight + navigationBarBottomSpacing + connectedDockSpacing
      isNavBarVisible && !isMiniPlayerBesideNavigation && !isDualPaneLayout -> navigationBarClearance
      else -> floatingBarSpacing
    }

  // Includes the complete stack below the player, but never system insets.
  val miniPlayerClearance: Dp
    get() = if (isMiniPlayerVisible) miniPlayerBottomSpacing + miniPlayerHeight + floatingBarSpacing else 0.dp

  val bottomOverlayClearance: Dp
    get() = maxOf(bottomBarClearance, miniPlayerClearance)

  internal fun canPlaceMiniPlayerBesideNavigation(availableWidth: Dp): Boolean =
    availableWidth >= navigationPillMaxMargin + navigationPillMaxWidth + floatingBarSpacing + miniPlayerMinWidth + floatingBarSpacing

  var shouldHideNavigationBar: Boolean by mutableStateOf(false)
    private set

  var isPermissionDenied: Boolean by mutableStateOf(false)
    private set

  var isBrowserBottomBarVisible: Boolean by mutableStateOf(false)
    private set

  var onlyVideosSelected: Boolean by mutableStateOf(false)
    private set

  fun updateSelectionState(
    inSelectionMode: Boolean,
    onlyVideos: Boolean = false,
  ) {
    isInSelectionMode = inSelectionMode
    onlyVideosSelected = onlyVideos
    // Selection actions replace the navigation pill instead of stacking above it.
    // Publish the clearance state immediately; MainScreen still animates the actual nav out.
    if (inSelectionMode) isNavBarVisible = false
    shouldHideNavigationBar = inSelectionMode
  }

  fun updatePermissionState(denied: Boolean) {
    isPermissionDenied = denied
  }

  fun updateBottomBarVisibility(visible: Boolean) {
    isBrowserBottomBarVisible = visible
    shouldHideNavigationBar = !visible
  }

  // Scroll-adaptive nav labels: 1f shows icon + label, 0f collapses to icons only so the pill
  // stays legible on narrow screens and gets out of the way while reading a long list.
  var navLabelVisibility: Float by mutableFloatStateOf(1f)
    private set

  private var accumulatedScroll = 0f

  /** Uptime of the latest content scroll delta; plain field so scrolling never recomposes. */
  @Volatile var lastContentScrollUptimeMs = 0L
    private set

  /** Attach once around the tab content; vertical deltas bubble up from the inner lists. */
  val navScrollConnection: NestedScrollConnection =
    object : NestedScrollConnection {
      override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        lastContentScrollUptimeMs = android.os.SystemClock.uptimeMillis()
        val delta = available.y
        // Reversing direction restarts the run so a small bounce does not toggle the labels.
        if ((delta < 0f && accumulatedScroll > 0f) || (delta > 0f && accumulatedScroll < 0f)) {
          accumulatedScroll = 0f
        }
        accumulatedScroll += delta
        if (accumulatedScroll < -SCROLL_THRESHOLD_PX) {
          navLabelVisibility = 0f
          accumulatedScroll = 0f
        } else if (accumulatedScroll > SCROLL_THRESHOLD_PX) {
          navLabelVisibility = 1f
          accumulatedScroll = 0f
        }
        return Offset.Zero
      }
    }

  fun expandNavLabels() {
    navLabelVisibility = 1f
    accumulatedScroll = 0f
  }

  private const val SCROLL_THRESHOLD_PX = 60f
}

@Composable
internal fun rememberMiniPlayerShape(): RoundedCornerShape {
  val joiningCorner = 20.dp * (1f - NavigationBarState.dockProgress) +
    NavigationBarState.connectedDockCorner * NavigationBarState.dockProgress
  return AppShapeScale.largeIncreased.copy(bottomStart = CornerSize(joiningCorner), bottomEnd = CornerSize(joiningCorner))
}
