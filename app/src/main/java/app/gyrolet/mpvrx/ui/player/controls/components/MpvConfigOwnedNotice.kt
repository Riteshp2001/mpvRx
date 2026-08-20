/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanel
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun MpvConfigOwnedSheet(onDismissRequest: () -> Unit) {
  PlayerSheet(onDismissRequest) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      MpvConfigOwnedContent()
      TextButton(
        onClick = onDismissRequest,
        modifier = Modifier.align(Alignment.End),
      ) {
        Text(stringResource(R.string.generic_ok))
      }
    }
  }
}

@Composable
fun MpvConfigOwnedPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  DraggablePanel(
    modifier = modifier,
    header = {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.mpv_config_owned_title),
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismissRequest) {
          Icon(Icons.RoundedFilled.Close, null, modifier = Modifier.size(32.dp))
        }
      }
    },
  ) {
    MpvConfigOwnedContent(
      modifier = Modifier.padding(MaterialTheme.spacing.medium),
      showTitle = false,
    )
  }
}

@Composable
private fun MpvConfigOwnedContent(
  modifier: Modifier = Modifier,
  showTitle: Boolean = true,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Code,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(28.dp),
    )
    if (showTitle) {
      Text(
        text = stringResource(R.string.mpv_config_owned_title),
        style = MaterialTheme.typography.titleLarge,
      )
    }
    Text(
      text = stringResource(R.string.mpv_config_owned_message),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
