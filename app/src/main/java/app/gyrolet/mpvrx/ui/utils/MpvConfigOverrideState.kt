/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject

@Composable
fun MpvConfigOverride.isOwnedByMpvConf(): Boolean {
  val selectedOptions = currentMpvConfigOverrideOptions()
  return optionNames.any(selectedOptions::contains)
}

@Composable
fun isMpvOptionOwnedByConfig(optionName: String): Boolean = optionName in currentMpvConfigOverrideOptions()

@Composable
fun isAnyMpvOptionOwnedByConfig(optionNames: Set<String>): Boolean {
  val selectedOptions = currentMpvConfigOverrideOptions()
  return optionNames.any(selectedOptions::contains)
}

@Composable
fun currentMpvConfigOverrideOptions(): Set<String> {
  val preferences = koinInject<AdvancedPreferences>()
  val storedValues by preferences.mpvConfOverrides.collectAsState()
  return remember(storedValues) { MpvConfigOverride.resolveOptionNames(storedValues) }
}