/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.tv.tvContentFocus

import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.lua.LuaRuntimeStatusCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptToggleCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptsEmptyState
import app.gyrolet.mpvrx.ui.lua.LuaScriptsLoadingState
import app.gyrolet.mpvrx.ui.lua.LuaSelectionFootnote
import app.gyrolet.mpvrx.ui.lua.rememberLuaScriptsCatalog
import app.gyrolet.mpvrx.ui.lua.resolveLuaScriptsDir
import app.gyrolet.mpvrx.ui.lua.shareLuaScriptFile
import app.gyrolet.mpvrx.ui.lua.toggleLuaScriptSelection
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalRootBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

@Serializable
object LuaScriptsScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val editorBackStack = LocalRootBackStack.current ?: backStack
    val preferences = koinInject<AdvancedPreferences>()
    val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
    val selectedScripts by preferences.selectedLuaScripts.collectAsState()
    val enableLuaScripts by preferences.enableLuaScripts.collectAsState()
    val catalog =
      rememberLuaScriptsCatalog(
        storageUri = mpvConfStorageLocation,
        selectedScripts = selectedScripts,
        onSelectionPruned = preferences.selectedLuaScripts::set,
        refreshKey = editorBackStack.size to backStack.size,
      )
    val enabledScriptsCount = selectedScripts.count { it in catalog.availableScripts }
    val scriptsListEnabled = enableLuaScripts && mpvConfStorageLocation.isNotBlank()

    fun shareScript(scriptName: String) {
      if (mpvConfStorageLocation.isBlank()) {
        Toast
          .makeText(
            context,
            context.getString(R.string.ui_no_storage_location_configured),
            Toast.LENGTH_SHORT,
          ).show()
        return
      }

      runCatching {
        val scriptFile =
          resolveLuaScriptsDir(context, mpvConfStorageLocation)
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.name == scriptName }

        if (scriptFile != null) {
          val cacheFile = File(context.cacheDir, scriptName)
          context.contentResolver.openInputStream(scriptFile.uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
          }
          shareLuaScriptFile(context, scriptName, cacheFile)
        } else {
          Toast
            .makeText(
              context,
              context.getString(R.string.ui_script_file_not_found),
              Toast.LENGTH_SHORT,
            ).show()
        }
      }.onFailure { error ->
        Toast
          .makeText(
            context,
            context.getString(
              R.string.toast_error_sharing_script,
              error.message ?: context.getString(R.string.generic_unknown_error),
            ),
            Toast.LENGTH_LONG,
          ).show()
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.pref_section_scripts),
              style = MaterialTheme.typography.headlineSmall,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(
                imageVector = Icons.RoundedFilled.ArrowBack,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.back),
              )
            }
          },
        )
      },
      floatingActionButton = {
        FloatingActionButton(
          onClick = { editorBackStack.navigateTo(LuaScriptEditorScreen(scriptName = null)) },
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.padding(bottom = app.gyrolet.mpvrx.ui.browser.fab.FabPlacement.scaffoldBottomPadding()),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Add,
            contentDescription =
              androidx.compose.ui.res.stringResource(
                app.gyrolet.mpvrx.R.string.ui_create_new_script,
              ),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      },
    ) { padding ->
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .tvContentFocus()
            .padding(padding),
        contentPadding =
          settingsContentPadding(
            PaddingValues(
              start = MaterialTheme.spacing.medium,
              top = MaterialTheme.spacing.medium,
              end = MaterialTheme.spacing.medium,
              // Extra clearance so the FAB doesn't overlap the last item
              bottom = MaterialTheme.spacing.medium + 80.dp,
            ),
          ),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        item {
          LuaRuntimeStatusCard(
            enabled = enableLuaScripts,
            hasStorageLocation = mpvConfStorageLocation.isNotBlank(),
            enabledScriptsCount = enabledScriptsCount,
            availableScriptsCount = catalog.availableScripts.size,
            onEnabledChange = preferences.enableLuaScripts::set,
            shape = rememberConnectedShape(0, catalog.availableScripts.size + 1, ConnectedLayout.Vertical),
          )
        }

        when {
          catalog.isLoading -> {
            item {
              LuaScriptsLoadingState()
            }
          }
          mpvConfStorageLocation.isBlank() -> {
            item {
              LuaScriptsEmptyState(
                title = stringResource(R.string.lua_no_mpv_folder),
                summary = "Choose an MPV config folder in Advanced settings to browse and manage scripts.",
              )
            }
          }
          catalog.availableScripts.isEmpty() -> {
            item {
              LuaScriptsEmptyState(
                title = stringResource(R.string.lua_no_scripts_found),
                summary = "Put your .lua or .js files inside the MPV scripts folder to manage them here.",
              )
            }
          }
          else -> {
            itemsIndexed(catalog.availableScripts, key = { _, name -> name }) { index, scriptName ->
              LuaScriptToggleCard(
                scriptName = scriptName,
                shape = rememberConnectedShape(index + 1, catalog.availableScripts.size + 1, ConnectedLayout.Vertical),
                selected = selectedScripts.contains(scriptName),
                controlsEnabled = scriptsListEnabled,
                onToggle = { toggleLuaScriptSelection(preferences, scriptName) },
                trailingContent = {
                  IconButton(onClick = { shareScript(scriptName) }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Share,
                      contentDescription =
                        androidx.compose.ui.res.stringResource(
                          app.gyrolet.mpvrx.R.string.generic_share,
                        ),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(onClick = { editorBackStack.navigateTo(LuaScriptEditorScreen(scriptName = scriptName)) }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Edit,
                      contentDescription =
                        androidx.compose.ui.res
                          .stringResource(app.gyrolet.mpvrx.R.string.ui_edit),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                },
              )
            }
          }
        }

        item {
          Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
          ) {
            LuaSelectionFootnote()
          }
        }
      }
    }
  }
}
