/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import me.zhanghai.compose.preference.Preference

/** Entry row in Advanced settings; the ownership choices live on [MpvConfOwnershipScreen]. */
@Composable
internal fun MpvConfigOverridePreference(
  preferences: AdvancedPreferences,
  modifier: Modifier = Modifier,
) {
  val backStack = LocalBackStack.current
  val storedValues by preferences.mpvConfOverrides.collectAsState()
  val selectedOptions = remember(storedValues) { MpvConfigOverride.resolveOptionNames(storedValues) }
  val selectedGroups = remember(selectedOptions) { MpvConfigOverride.groupsContaining(selectedOptions) }

  Preference(
    modifier = modifier,
    title = { Text(stringResource(R.string.pref_mpv_conf_overrides_title)) },
    summary = {
      Text(
        text =
          if (selectedOptions.isEmpty()) {
            stringResource(R.string.pref_mpv_conf_overrides_summary_app_owned)
          } else {
            stringResource(
              R.string.pref_mpv_conf_overrides_summary_config_owned,
              selectedGroups.size,
              selectedOptions.size,
            )
          },
        color = MaterialTheme.colorScheme.outline,
      )
    },
    onClick = { backStack.add(MpvConfOwnershipScreen) },
  )
}
