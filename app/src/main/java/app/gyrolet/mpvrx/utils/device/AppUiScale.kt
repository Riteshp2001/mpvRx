/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.device

import android.content.Context
import android.content.res.Configuration

const val MIN_APP_UI_SCALE = 0.5f
const val MAX_APP_UI_SCALE = 1.5f

/**
 * Builds the activity override configuration for the app UI scale preference.
 *
 * Only density is overridden: the framework re-merges this delta on every configuration change, so
 * orientation-dependent fields such as `screenWidthDp` would stay pinned to their startup values
 * and break rotation.
 */
fun appUiScaleOverrideConfiguration(
  base: Context,
  scale: Float,
): Configuration =
  Configuration().apply {
    val systemDensityDpi = base.resources.configuration.densityDpi
    densityDpi = (systemDensityDpi * scale.coerceIn(MIN_APP_UI_SCALE, MAX_APP_UI_SCALE)).toInt()
  }
