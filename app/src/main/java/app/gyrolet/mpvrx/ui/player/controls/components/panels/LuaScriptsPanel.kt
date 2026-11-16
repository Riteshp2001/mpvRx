/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.lua.LuaRuntimeStatusCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptToggleCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptsEmptyState
import app.gyrolet.mpvrx.ui.lua.LuaScriptsLoadingState
import app.gyrolet.mpvrx.ui.lua.LuaSelectionFootnote
import app.gyrolet.mpvrx.ui.lua.rememberLuaScriptsCatalog
import app.gyrolet.mpvrx.ui.lua.toggleLuaScriptSelection
import app.gyrolet.mpvrx.ui.theme.spacing
import org.koin.compose.koinInject

@Composable
fun LuaScriptsPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val preferences = koinInject<AdvancedPreferences>()
  val enableLuaScripts by preferences.enableLuaScripts.collectAsState()
  val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
  val selectedScripts by preferences.selectedLuaScripts.collectAsState()
  val catalog =
    rememberLuaScriptsCatalog(
      storageUri = mpvConfStorageLocation,
      selectedScripts = selectedScripts,
      onSelectionPruned = preferences.selectedLuaScripts::set,
    )
  val enabledScriptsCount = selectedScripts.count { it in catalog.availableScripts }
  val scriptsListEnabled = enableLuaScripts && mpvConfStorageLocation.isNotBlank()
  val connectedItemCount =
    if (catalog.isLoading || mpvConfStorageLocation.isBlank()) 1 else catalog.availableScripts.size + 1

  DraggablePanel(
    modifier = modifier,
    header = {
      DraggablePanelHeader(
        title = stringResource(R.string.pref_section_scripts),
        onDismissRequest = onDismissRequest,
      )
    },
  ) {
    Column(
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.medium)
          .imePadding(),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      LuaRuntimeStatusCard(
        enabled = enableLuaScripts,
        hasStorageLocation = mpvConfStorageLocation.isNotBlank(),
        enabledScriptsCount = enabledScriptsCount,
        availableScriptsCount = catalog.availableScripts.size,
        onEnabledChange = preferences.enableLuaScripts::set,
        shape = rememberConnectedShape(0, connectedItemCount, ConnectedLayout.Vertical),
      )

      when {
        catalog.isLoading -> {
          LuaScriptsLoadingState()
        }
        mpvConfStorageLocation.isBlank() -> {
          LuaScriptsEmptyState(
            title = stringResource(R.string.lua_no_mpv_folder),
            summary = "Choose an MPV config folder in Advanced settings, then open this panel again to manage scripts.",
          )
        }
        catalog.availableScripts.isEmpty() -> {
          LuaScriptsEmptyState(
            title = stringResource(R.string.lua_no_scripts_found),
            summary = "Put your .lua or .js files inside the MPV scripts folder to manage them here.",
          )
        }
        else -> {
          catalog.availableScripts.forEachIndexed { index, scriptName ->
            LuaScriptToggleCard(
              scriptName = scriptName,
              shape = rememberConnectedShape(index + 1, connectedItemCount, ConnectedLayout.Vertical),
              selected = selectedScripts.contains(scriptName),
              controlsEnabled = scriptsListEnabled,
              onToggle = { toggleLuaScriptSelection(preferences, scriptName) },
            )
          }
        }
      }

      Box(Modifier.padding(top = MaterialTheme.spacing.medium)) {
        LuaSelectionFootnote()
      }
    }
  }
}
