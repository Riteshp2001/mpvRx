/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
internal class BrowserSortDialogState(
  val isOpen: MutableState<Boolean>,
  val content: @Composable (@Composable () -> Unit) -> Unit,
)

internal val LocalBrowserSortDialogState = staticCompositionLocalOf<BrowserSortDialogState?> { null }

/** Keep Home's sheet and its remembered state alive while the browser mode below it is replaced. */
@Composable
internal fun BrowserSortDialogScope(content: @Composable () -> Unit) {
  val isOpen = rememberSaveable { mutableStateOf(false) }
  val sheetContent =
    remember {
      movableContentOf<@Composable () -> Unit> { body -> body() }
    }
  val state = remember(isOpen, sheetContent) { BrowserSortDialogState(isOpen, sheetContent) }
  CompositionLocalProvider(LocalBrowserSortDialogState provides state, content = content)
}

/** Embedded music lists and standalone directory screens keep their own independent dialog. */
@Composable
internal fun rememberBrowserSortDialogOpen(useHomeState: Boolean = true): MutableState<Boolean> {
  val localState = rememberSaveable { mutableStateOf(false) }
  return if (useHomeState) LocalBrowserSortDialogState.current?.isOpen ?: localState else localState
}
