/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.SliderItem
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.spacing
import org.koin.compose.koinInject

@Composable
fun VideoZoomSheet(
  videoZoom: Float,
  onSetVideoZoom: (Float) -> Unit,
  onResetVideoPan: () -> Unit,
  onDismissRequest: () -> Unit,
  zoomControlEnabled: Boolean = true,
  panControlEnabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val defaultZoom by playerPreferences.defaultVideoZoom.collectAsState()
  val panAndZoomEnabled by playerPreferences.panAndZoomEnabled.collectAsState()
  var zoom by remember { mutableFloatStateOf(videoZoom) }

  val currentOnSetVideoZoom by rememberUpdatedState(onSetVideoZoom)

  LaunchedEffect(Unit) {
    val mpvZoom = PlaybackSession.getPropertyDouble("video-zoom")?.toFloat() ?: videoZoom
    zoom = mpvZoom
  }

  LaunchedEffect(zoom) {
    if (zoomControlEnabled) currentOnSetVideoZoom(zoom)
  }

  PlayerSheet(onDismissRequest = onDismissRequest) {
    ZoomVideoSheet(
      zoom = zoom,
      defaultZoom = defaultZoom,
      panAndZoomEnabled = panAndZoomEnabled,
      onZoomChange = { newZoom -> zoom = newZoom },
      onSetAsDefault = {
        if (zoomControlEnabled) playerPreferences.defaultVideoZoom.set(zoom)
      },
      onReset = {
        if (zoomControlEnabled) {
          zoom = 0f
          playerPreferences.defaultVideoZoom.set(0f)
        }
        if (panControlEnabled) onResetVideoPan()
      },
      onPanAndZoomToggle = { enabled ->
        playerPreferences.panAndZoomEnabled.set(enabled)
        if (!enabled) {
          onResetVideoPan()
        }
      },
      zoomControlEnabled = zoomControlEnabled,
      panControlEnabled = panControlEnabled,
      modifier = modifier,
    )
  }
}

@Composable
private fun ZoomVideoSheet(
  zoom: Float,
  defaultZoom: Float,
  panAndZoomEnabled: Boolean,
  onZoomChange: (Float) -> Unit,
  onSetAsDefault: () -> Unit,
  onReset: () -> Unit,
  onPanAndZoomToggle: (Boolean) -> Unit,
  zoomControlEnabled: Boolean,
  panControlEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val isDefault = zoom == defaultZoom
  val isZero = zoom == 0f

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(vertical = MaterialTheme.spacing.medium),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
  ) {
    // Zoom slider with +/- buttons
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      FilledTonalIconButton(
        onClick = {
          val newZoom = (zoom - 0.01f).coerceAtLeast(-1f)
          onZoomChange(newZoom)
        },
        enabled = zoomControlEnabled,
        modifier = Modifier.size(36.dp),
      ) {
        Icon(
          Icons.RoundedFilled.Remove,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_decrease_zoom),
          modifier = Modifier.size(18.dp),
        )
      }

      SliderItem(
        label = stringResource(id = R.string.player_sheets_zoom_slider_label),
        value = zoom,
        valueText = "%.2fx".format(zoom),
        onChange = onZoomChange,
        max = 3f,
        min = -1f,
        enabled = zoomControlEnabled,
        modifier = Modifier.weight(1f),
      )

      FilledTonalIconButton(
        onClick = {
          val newZoom = (zoom + 0.01f).coerceAtMost(3f)
          onZoomChange(newZoom)
        },
        enabled = zoomControlEnabled,
        modifier = Modifier.size(36.dp),
      ) {
        Icon(
          Icons.RoundedFilled.Add,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_increase_zoom),
          modifier = Modifier.size(18.dp),
        )
      }
    }

    HorizontalDivider(
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )

    // Pan & Zoom toggle + action buttons
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      // Pan & Zoom toggle
      Row(
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconSwitch(
          checked = panAndZoomEnabled,
          onCheckedChange = onPanAndZoomToggle,
          modifier = Modifier.scale(0.8f),
          enabled = panControlEnabled,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_pan_zoom),
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (!panControlEnabled) {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            } else if (panAndZoomEnabled) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }

      // Action buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onSetAsDefault,
          enabled = zoomControlEnabled && !isDefault,
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.set_as_default), style = MaterialTheme.typography.labelMedium)
        }

        Button(
          onClick = onReset,
          enabled = (zoomControlEnabled && !isZero) || panControlEnabled,
          modifier = Modifier.weight(1f),
        ) {
          Text(stringResource(R.string.generic_reset), style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}
