/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.motion.MotionAvailability
import app.gyrolet.mpvrx.domain.motion.MotionInterpolation
import app.gyrolet.mpvrx.domain.motion.MotionTargetMode
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.preferences.components.AppSliderPreference
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference

/**
 * GPU motion interpolation settings (FongMi build). An open player applies every change
 * immediately; switching on waits until GPU Next + Vulkan is selected on a Vulkan device.
 */
@Composable
internal fun MotionInterpolationPreferences(
  preferences: DecoderPreferences,
  rendererConfigOwned: Boolean,
) {
  val context = LocalContext.current
  val enabled by preferences.motionInterpolationEnabled.collectAsState()
  val gpuNext by preferences.gpuNext.collectAsState()
  val useVulkan by preferences.useVulkan.collectAsState()
  val mode by preferences.motionInterpolationMode.collectAsState()
  val targetFps by preferences.motionInterpolationTargetFps.collectAsState()
  val switchDisplayRate by preferences.motionInterpolationSwitchDisplayRate.collectAsState()

  val barrier = remember(gpuNext, useVulkan) { MotionInterpolation.barrier(context, preferences) }
  // Keep the stored switch in step with the barrier, e.g. after Vulkan is switched off.
  LaunchedEffect(barrier, enabled) {
    if (enabled && barrier.blocksEnabling) preferences.motionInterpolationEnabled.set(false)
  }
  val platformSupported =
    barrier != MotionAvailability.UNSUPPORTED_BUILD && barrier != MotionAvailability.UNSUPPORTED_DEVICE
  val maxDisplayRate = remember { MotionInterpolation.maxDisplayRefreshRate(context)?.roundToInt() }
  val maxTargetFps = maxDisplayRate?.coerceIn(60, MotionInterpolation.MAX_TARGET_FPS) ?: MotionInterpolation.MAX_TARGET_FPS

  PreferenceCard {
    SwitchPreference(
      modifier = Modifier.settingsSearchTarget(R.string.motion_title),
      value = enabled && !barrier.blocksEnabling,
      onValueChange = { preferences.motionInterpolationEnabled.set(it) },
      // Off is always reachable; on waits for the requirements below.
      enabled = enabled || !barrier.blocksEnabling,
      title = { Text(stringResource(R.string.motion_title)) },
      summary = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.motion_summary), color = MaterialTheme.colorScheme.outline)
          barrier.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
          if (barrier == MotionAvailability.NEEDS_RENDERER && !rendererConfigOwned) {
            FilledTonalButton(
              onClick = {
                preferences.gpuNext.set(true)
                preferences.useVulkan.set(true)
              },
              modifier = Modifier.align(Alignment.End),
            ) {
              Text(stringResource(R.string.motion_use_gpu_next_vulkan))
            }
          }
        }
      },
    )

    if (platformSupported) {
      PreferenceDivider()

      ExpressivePreferenceItem {
        ListPreference(
          modifier = Modifier.settingsSearchTarget(R.string.motion_target_title),
          value = mode,
          onValueChange = { preferences.motionInterpolationMode.set(it) },
          values = MotionTargetMode.entries,
          valueToText = { AnnotatedString(context.getString(it.titleRes)) },
          title = { Text(stringResource(R.string.motion_target_title)) },
          summary = {
            Text(
              "${stringResource(mode.titleRes)} · ${stringResource(mode.summaryRes)}",
              color = MaterialTheme.colorScheme.outline,
            )
          },
        )
      }

      if (mode == MotionTargetMode.FIXED) {
        PreferenceDivider()
        var sliderFps by remember(targetFps) { mutableFloatStateOf(targetFps.coerceAtMost(maxTargetFps).toFloat()) }
        AppSliderPreference(
          modifier = Modifier.settingsSearchTarget(R.string.motion_target_fps_title),
          value = targetFps.coerceAtMost(maxTargetFps).toFloat(),
          onValueChange = { preferences.motionInterpolationTargetFps.set(it.roundToInt()) },
          sliderValue = sliderFps,
          onSliderValueChange = { sliderFps = it },
          valueRange = MotionInterpolation.MIN_TARGET_FPS.toFloat()..maxTargetFps.toFloat(),
          valueSteps = maxTargetFps - MotionInterpolation.MIN_TARGET_FPS - 1,
          title = { Text(stringResource(R.string.motion_target_fps_title)) },
          summary = {
            Text(
              stringResource(R.string.motion_fps_value, sliderFps.roundToInt()),
              color = MaterialTheme.colorScheme.outline,
            )
          },
        )
      }

      PreferenceDivider()

      SwitchPreference(
        modifier = Modifier.settingsSearchTarget(R.string.motion_switch_display_title),
        value = switchDisplayRate,
        onValueChange = { preferences.motionInterpolationSwitchDisplayRate.set(it) },
        title = { Text(stringResource(R.string.motion_switch_display_title)) },
        summary = {
          Text(stringResource(R.string.motion_switch_display_summary), color = MaterialTheme.colorScheme.outline)
        },
      )

      PreferenceDivider()

      ExpressivePreferenceItem {
        Text(
          stringResource(R.string.motion_requirements_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    }
  }
}
