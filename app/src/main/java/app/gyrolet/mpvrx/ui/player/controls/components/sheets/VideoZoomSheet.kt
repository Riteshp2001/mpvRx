/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.ui.components.AppSlider as Slider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.connectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics
import org.koin.compose.koinInject

@Composable
fun VideoZoomSheet(
  videoZoom: Float,
  onSetVideoZoom: (Float) -> Unit,
  snapVideoZoom: (Float) -> Float,
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

  LaunchedEffect(zoom) {
    if (zoomControlEnabled) currentOnSetVideoZoom(zoom)
  }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = stringResource(R.string.btn_label_zoom),
  ) {
    ZoomVideoSheet(
      zoom = zoom,
      panAndZoomEnabled = panAndZoomEnabled,
      onZoomChange = { newZoom -> zoom = newZoom },
      onZoomChangeFinished = { zoom = snapVideoZoom(zoom) },
      onPanAndZoomToggle = { enabled ->
        playerPreferences.panAndZoomEnabled.set(enabled)
        if (!enabled) {
          onResetVideoPan()
        }
      },
      zoomControlEnabled = zoomControlEnabled,
      panControlEnabled = panControlEnabled,
      modifier = modifier,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      ) {
        Button(
          onClick = { playerPreferences.defaultVideoZoom.set(zoom) },
          enabled = zoomControlEnabled && zoom != defaultZoom,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.set_as_default), style = MaterialTheme.typography.labelMedium)
        }

        FilledTonalButton(
          onClick = {
            if (zoomControlEnabled) {
              zoom = 0f
              playerPreferences.defaultVideoZoom.set(0f)
            }
            if (panControlEnabled) onResetVideoPan()
          },
          enabled = (zoomControlEnabled && zoom != 0f) || panControlEnabled,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
          Text(stringResource(R.string.generic_reset), style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}

@Composable
private fun ZoomVideoSheet(
  zoom: Float,
  panAndZoomEnabled: Boolean,
  onZoomChange: (Float) -> Unit,
  onZoomChangeFinished: () -> Unit,
  onPanAndZoomToggle: (Boolean) -> Unit,
  zoomControlEnabled: Boolean,
  panControlEnabled: Boolean,
  modifier: Modifier = Modifier,
  actions: @Composable () -> Unit,
) {
  val preferences = koinInject<PlayerPreferences>()
  val overlaysFollowZoom by preferences.overlaysFollowVideoZoom.collectAsState()
  val zoomLabel = stringResource(R.string.player_sheets_zoom_slider_label)
  val zoomValueText = "%.2fx".format(zoom)
  val zoomHaptics = rememberAdjustmentHaptics(-1f, 3f)

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = zoomValueText,
      modifier = Modifier.fillMaxWidth(),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )

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
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          Icons.RoundedFilled.Remove,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_decrease_zoom),
          modifier = Modifier.size(24.dp),
        )
      }

      Slider(
        value = zoom,
        onValueChange = { newZoom ->
          if (newZoom != zoom) {
            onZoomChange(newZoom)
            zoomHaptics.move(zoom, newZoom)
          }
        },
        onValueChangeFinished = onZoomChangeFinished,
        valueRange = -1f..3f,
        enabled = zoomControlEnabled,
        modifier = Modifier.weight(1f)
          .semantics {
            contentDescription = zoomLabel
            stateDescription = zoomValueText
          }
          .tvFocusHighlight(MaterialTheme.shapes.small, enabled = zoomControlEnabled),
      )

      FilledTonalIconButton(
        onClick = {
          val newZoom = (zoom + 0.01f).coerceAtMost(3f)
          onZoomChange(newZoom)
        },
        enabled = zoomControlEnabled,
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          Icons.RoundedFilled.Add,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_increase_zoom),
          modifier = Modifier.size(24.dp),
        )
      }
    }

    HorizontalDivider(
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )

    // Pan & Zoom toggles
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      ZoomOptionSwitch(
        title = stringResource(R.string.ui_pan_zoom),
        checked = panAndZoomEnabled,
        onCheckedChange = onPanAndZoomToggle,
        enabled = panControlEnabled,
        groupIndex = 0,
      )

      ZoomOptionSwitch(
        title = stringResource(R.string.player_zoom_overlays_follow),
        summary = stringResource(R.string.player_zoom_overlays_follow_summary),
        checked = overlaysFollowZoom,
        onCheckedChange = { preferences.overlaysFollowVideoZoom.set(it) },
        groupIndex = 1,
      )
    }
    actions()
  }
}

@Composable
private fun ZoomOptionSwitch(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  groupIndex: Int,
  summary: String? = null,
  enabled: Boolean = true,
) {
  val shape = connectedShape(groupIndex, 2, ConnectedLayout.Vertical)
  val contentAlpha = if (enabled) 1f else 0.38f
  Row(
    modifier = Modifier.fillMaxWidth()
      .heightIn(min = 48.dp)
      .tvFocusHighlight(shape, enabled = enabled, focusedScale = 1.01f)
      .animatedPlayerSheetBackground(checked, shape)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
      if (summary != null) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha))
      }
    }
    IconSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}
