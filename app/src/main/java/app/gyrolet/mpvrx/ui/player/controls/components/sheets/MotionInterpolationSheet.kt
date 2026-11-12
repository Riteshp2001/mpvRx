/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.motion.MotionAvailability
import app.gyrolet.mpvrx.domain.motion.MotionState
import app.gyrolet.mpvrx.domain.motion.MotionTargetMode
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.motion.MotionInterpolationController
import app.gyrolet.mpvrx.ui.player.motion.MotionLive
import app.gyrolet.mpvrx.ui.player.motion.formatFps
import app.gyrolet.mpvrx.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val STATUS_REFRESH_MS = 1_000L

/** A one-tap target with its Material symbol; a null rate is Auto. */
private data class FpsTile(
  val fps: Int?,
  val icon: AppIcon,
)

@Composable
fun MotionInterpolationSheet(
  controller: MotionInterpolationController,
  onDismissRequest: () -> Unit,
) {
  val preferences = koinInject<DecoderPreferences>()
  val state by controller.state.collectAsState()
  val config = state.config
  val availability = state.availability
  val running = state.running
  val optionsEnabled =
    availability != MotionAvailability.UNSUPPORTED_BUILD &&
      availability != MotionAvailability.UNSUPPORTED_DEVICE

  LaunchedEffect(Unit) { controller.refresh() }

  // The video and display rates settle a moment after a change, so follow them while open.
  var live by remember { mutableStateOf(MotionLive.EMPTY) }
  LaunchedEffect(running) {
    if (!running) return@LaunchedEffect
    while (isActive) {
      live = withContext(Dispatchers.IO) { controller.live() }
      delay(STATUS_REFRESH_MS)
    }
  }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = stringResource(R.string.motion_title),
    bottomBar = {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(
          onClick = {
            preferences.motionInterpolationMode.delete()
            preferences.motionInterpolationTargetFps.delete()
          },
          enabled = optionsEnabled,
        ) {
          Text(stringResource(R.string.generic_reset), style = MaterialTheme.typography.labelMedium)
        }
      }
    },
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      MasterSwitch(
        checked = config.enabled,
        running = running,
        availability = availability,
        live = live,
        onCheckedChange = controller::setEnabled,
      )

      if (availability != MotionAvailability.READY) {
        BarrierCard(
          availability = availability,
          onEnableRenderer = { controller.enableRequiredRenderer() },
        )
      }

      if (optionsEnabled) {
        Divider()

        SectionLabel(stringResource(R.string.motion_target_title))
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf(
            FpsTile(60, Icons.RoundedFilled.Fps60),
            FpsTile(30, Icons.RoundedFilled.Fps30),
            FpsTile(null, Icons.RoundedFilled.FpsAuto),
            FpsTile(24, Icons.RoundedFilled.Fps24),
          ).forEach { tile ->
            val selected =
              if (tile.fps == null) {
                config.mode == MotionTargetMode.AUTO
              } else {
                config.mode == MotionTargetMode.FIXED && config.targetFps == tile.fps
              }
            FpsTileButton(
              icon = tile.icon,
              label =
                if (tile.fps == null) {
                  stringResource(R.string.motion_target_auto)
                } else {
                  stringResource(R.string.motion_fps_value, tile.fps)
                },
              selected = selected,
              onClick = {
                if (tile.fps == null) {
                  controller.selectTarget(MotionTargetMode.AUTO)
                } else {
                  controller.selectTarget(MotionTargetMode.FIXED, tile.fps)
                }
              },
              modifier = Modifier.weight(1f),
            )
          }
        }

        Text(
          text = stringResource(config.mode.summaryRes),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )
      }
    }
  }
}

@Composable
private fun MasterSwitch(
  checked: Boolean,
  running: Boolean,
  availability: MotionAvailability,
  live: MotionLive,
  onCheckedChange: (Boolean) -> Unit,
) {
  val shape = MaterialTheme.shapes.large
  // Switching off is always allowed; switching on waits for the hard requirements.
  val toggleEnabled = checked || !availability.blocksEnabling
  val unknown = stringResource(R.string.motion_rate_unknown)
  val liveState = live.state
  val status =
    when {
      !running -> stringResource(if (checked) R.string.motion_status_waiting else R.string.motion_status_off)
      // Why nothing is being generated right now: passthrough, paused, no frames, or a GPU error.
      liveState != null && liveState in MotionState.NOT_GENERATING -> stringResource(liveState.labelRes)
      else ->
        stringResource(
          R.string.motion_rates,
          live.videoFps?.let(::formatFps) ?: unknown,
          live.outputFps?.let(::formatFps) ?: unknown,
          live.displayFps?.let(::formatFps) ?: unknown,
        )
    }
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium)
        .heightIn(min = 56.dp)
        .tvFocusHighlight(shape, enabled = toggleEnabled, focusedScale = 1.01f)
        .animatedPlayerSheetBackground(running, shape)
        .toggleable(value = checked, enabled = toggleEnabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.MotionInterpolation,
      contentDescription = null,
      tint =
        if (running && liveState == MotionState.ACTIVE) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.size(28.dp),
    )
    Column(Modifier.weight(1f)) {
      Text(
        stringResource(R.string.motion_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color =
          when {
            running && (liveState == MotionState.ERROR || liveState == MotionState.UNSUPPORTED) ->
              MaterialTheme.colorScheme.error
            running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
    IconSwitch(checked = checked, onCheckedChange = null, enabled = toggleEnabled)
  }
}

@Composable
private fun BarrierCard(
  availability: MotionAvailability,
  onEnableRenderer: () -> Unit,
) {
  val message = availability.messageRes ?: return
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.RoundedFilled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
      }
      if (availability == MotionAvailability.NEEDS_RENDERER) {
        FilledTonalButton(onClick = onEnableRenderer, modifier = Modifier.align(Alignment.End)) {
          Text(stringResource(R.string.motion_use_gpu_next_vulkan))
        }
      }
    }
  }
}

@Composable
private fun FpsTileButton(
  icon: AppIcon,
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = MaterialTheme.shapes.large
  Column(
    modifier =
      modifier
        .heightIn(min = 76.dp)
        .tvFocusHighlight(shape, focusedScale = 1.03f)
        .animatedPlayerSheetBackground(selected, shape)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(30.dp),
    )
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
  )
}

@Composable
private fun Divider() {
  HorizontalDivider(
    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
  )
}
