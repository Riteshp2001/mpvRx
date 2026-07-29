/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
    onlyVideos: Boolean,
  ) {
    isInSelectionMode = inSelectionMode
    onlyVideosSelected = onlyVideos
    shouldHideNavigationBar = inSelectionMode && onlyVideos
  }

  fun updatePermissionState(denied: Boolean) {
    isPermissionDenied = denied
  }

  fun updateBottomBarVisibility(visible: Boolean) {
    isBrowserBottomBarVisible = visible
    shouldHideNavigationBar = !visible
  }
}
