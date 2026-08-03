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

/**
 * Centralized state holder for browser navigation visibility.
 * Uses Compose state so screens recompose only when values actually change.
 */
object NavigationBarState {
  var isInSelectionMode: Boolean by mutableStateOf(false)
    private set

  var isDualPaneFolderSelected: Boolean by mutableStateOf(false)

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
