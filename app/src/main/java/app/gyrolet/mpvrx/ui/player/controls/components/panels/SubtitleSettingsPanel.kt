/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun SubtitleSettingsPanel(
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  DraggablePanel(
    modifier = modifier,
    header = {
      DraggablePanelHeader(
        title = stringResource(R.string.player_sheets_subtitles_settings_title),
        onDismissRequest = onDismissRequest,
      )
    },
  ) {
    Column(
      Modifier.fillMaxWidth().padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      SubtitleSettingsTypographyCard(
        viewModel = viewModel,
        shape = rememberConnectedShape(0, 3, ConnectedLayout.Vertical),
      )
      SubtitleSettingsColorsCard(
        viewModel = viewModel,
        shape = rememberConnectedShape(1, 3, ConnectedLayout.Vertical),
      )
      SubtitlesMiscellaneousCard(shape = rememberConnectedShape(2, 3, ConnectedLayout.Vertical))
    }
  }
}
