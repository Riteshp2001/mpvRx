/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gyrolet.mpvrx.ui.utils.LocalNavigationPageActive

/** Only the foreground page may publish selection controls to the shared navigation bar. */
@Composable
internal fun NavigationBarSelectionEffect(
  inSelectionMode: Boolean,
  onlyVideos: Boolean = true,
) {
  val owner = remember { Any() }
  val isActive = LocalNavigationPageActive.current
  LifecycleResumeEffect(inSelectionMode, onlyVideos, isActive) {
    if (isActive) NavigationBarState.claimSelection(owner, inSelectionMode, onlyVideos)
    onPauseOrDispose { NavigationBarState.releaseSelection(owner) }
  }
}
