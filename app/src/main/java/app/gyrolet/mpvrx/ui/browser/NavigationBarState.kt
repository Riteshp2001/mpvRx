/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized state holder for browser navigation visibility.
 * Uses Compose state so screens recompose only when values actually change.
 */
object NavigationBarState {
  var isInSelectionMode: Boolean by mutableStateOf(false)
    private set

  var isDualPaneFolderSelected: Boolean by mutableStateOf(false)

  // Mini player coordination state (published by MiniPlayer/MainScreen so the
  // browser layout can adapt when the mini player is on screen).
  var isMiniPlayerVisible: Boolean by mutableStateOf(false)

  // True while the floating pill nav bar is actually on screen (MainScreen on top
  // and not hidden). Lets the mini player drop to the very bottom when there is no
  // nav bar below it (e.g. pushed video list screens).
  var isNavBarVisible: Boolean by mutableStateOf(false)

  var navbarLeftOffset: Dp by mutableStateOf(0.dp)

  var navbarWidth: Dp by mutableStateOf(320.dp)

  // Vertical space the mini player needs to clear from the bottom of the screen in
  // screens that sit below it without a nav bar (FABs/list content).
  val miniPlayerClearance: Dp
    get() = if (isMiniPlayerVisible) 96.dp else 0.dp

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
    shouldHideNavigationBar = inSelectionMode
  }

  fun updatePermissionState(denied: Boolean) {
    isPermissionDenied = denied
  }

  fun updateBottomBarVisibility(visible: Boolean) {
    isBrowserBottomBarVisible = visible
    shouldHideNavigationBar = !visible
  }
}
