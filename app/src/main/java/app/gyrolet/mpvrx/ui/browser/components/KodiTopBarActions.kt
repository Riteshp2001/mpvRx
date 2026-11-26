/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.kodi.KodiIntegration
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.icons.Icons

/**
 * Browser top-bar actions of builds that bundle Kodi: "Open Kodi" outside selection mode and
 * "Play with Kodi" while exactly one video ([selectedVideo]) is selected.
 */
@Composable
fun RowScope.KodiTopBarActions(
  isInSelectionMode: Boolean,
  showOpenKodi: Boolean = true,
  selectedVideo: Video? = null,
  onPlayStarted: () -> Unit = {},
) {
  val context = LocalContext.current
  val available = remember(context) { KodiIntegration.isAvailable(context) }
  if (!available) return

  if (!isInSelectionMode) {
    if (showOpenKodi) {
      BrowserTopBarActionButton(
        icon = Icons.RoundedFilled.Tv,
        contentDescription = stringResource(R.string.kodi_open),
        onClick = { KodiIntegration.open(context) },
      )
    }
  } else if (selectedVideo != null) {
    val cannotPlay = stringResource(R.string.kodi_cannot_play)
    BrowserTopBarActionButton(
      icon = Icons.RoundedFilled.SmartDisplay,
      contentDescription = stringResource(R.string.kodi_play_with),
      onClick = {
        if (KodiIntegration.play(context, selectedVideo)) {
          onPlayStarted()
        } else {
          AppToast.makeText(context, cannotPlay, AppToast.LENGTH_SHORT).show()
        }
      },
    )
  }
}
