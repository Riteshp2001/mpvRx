package app.gyrolet.mpvrx.ui.player.controls.components.panels

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.lua.LuaRuntimeStatusCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptToggleCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptsEmptyState
import app.gyrolet.mpvrx.ui.lua.LuaScriptsLoadingState
import app.gyrolet.mpvrx.ui.lua.LuaSelectionFootnote
import app.gyrolet.mpvrx.ui.lua.rememberLuaScriptsCatalog
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

  fun toggleScriptSelection(scriptName: String) {
    val isEnabled = selectedScripts.contains(scriptName)
    val newSelection =
      if (isEnabled) {
        Toast.makeText(
          context,
          "$scriptName disabled. Reopen the video if the script stays active.",
          Toast.LENGTH_LONG,
        ).show()
        selectedScripts - scriptName
      } else {
        selectedScripts + scriptName
      }
    preferences.selectedLuaScripts.set(newSelection)
  }

  DraggablePanel(
    modifier = modifier,
    header = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(top = MaterialTheme.spacing.small),
      ) {
        Text(
          text = "Scripts (Lua / JS)",
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismissRequest) {
          Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(32.dp))
        }
      }
    },
  ) {
    Column(
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.medium)
          .imePadding(),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      LuaRuntimeStatusCard(
        enabled = enableLuaScripts,
        hasStorageLocation = mpvConfStorageLocation.isNotBlank(),
        enabledScriptsCount = enabledScriptsCount,
        availableScriptsCount = catalog.availableScripts.size,
        onEnabledChange = preferences.enableLuaScripts::set,
      )

      when {
        catalog.isLoading -> {
          LuaScriptsLoadingState()
        }
        mpvConfStorageLocation.isBlank() -> {
          LuaScriptsEmptyState(
            title = "No MPV folder selected",
            summary = "Choose an MPV config folder in Advanced settings, then open this panel again to manage scripts.",
          )
        }
        catalog.availableScripts.isEmpty() -> {
          LuaScriptsEmptyState(
            title = "No scripts found",
            summary = "Put your .lua or .js files inside the MPV scripts folder to manage them here.",
          )
        }
        else -> {
          catalog.availableScripts.forEach { scriptName ->
            LuaScriptToggleCard(
              scriptName = scriptName,
              selected = selectedScripts.contains(scriptName),
              controlsEnabled = scriptsListEnabled,
              onToggle = { toggleScriptSelection(scriptName) },
            )
          }
        }
      }

      LuaSelectionFootnote()
    }
  }
}

