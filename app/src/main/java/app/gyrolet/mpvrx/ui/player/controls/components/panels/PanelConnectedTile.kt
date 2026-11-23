/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.liquidglass.playerGlassContainerColor
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape

/**
 * Consistent connected row for adjustable controls inside draggable player panels.
 * Keep the tonal fill visible even when the control is not selected or is disabled;
 * adjacent rows supply their position so only the group's exposed corners are large.
 */
@Composable
internal fun PanelConnectedTile(
  index: Int,
  itemCount: Int,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = rememberConnectedShape(index, itemCount, ConnectedLayout.Vertical),
    color = playerGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)),
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    content()
  }
}
