package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.lua.LuaRuntimeStatusCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptToggleCard
import app.gyrolet.mpvrx.ui.lua.LuaScriptsEmptyState
import app.gyrolet.mpvrx.ui.lua.LuaScriptsLoadingState
import app.gyrolet.mpvrx.ui.lua.LuaSelectionFootnote
import app.gyrolet.mpvrx.ui.lua.rememberLuaScriptsCatalog
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import java.io.File
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object LuaScriptsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
    val selectedScripts by preferences.selectedLuaScripts.collectAsState()
    val enableLuaScripts by preferences.enableLuaScripts.collectAsState()
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

    fun shareScript(scriptName: String) {
      if (mpvConfStorageLocation.isBlank()) {
        Toast.makeText(context, "No storage location configured", Toast.LENGTH_SHORT).show()
        return
      }

      runCatching {
        val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
        if (tree != null && tree.exists()) {
          val scriptsDir =
            tree.listFiles().firstOrNull {
              it.isDirectory && it.name?.equals("scripts", ignoreCase = true) == true
            } ?: tree

          val scriptFile =
            scriptsDir.listFiles().firstOrNull {
              it.isFile && it.name == scriptName
            }

          if (scriptFile != null) {
            val cacheFile = File(context.cacheDir, scriptName)
            context.contentResolver.openInputStream(scriptFile.uri)?.use { input ->
              cacheFile.outputStream().use { output ->
                input.copyTo(output)
              }
            }

            val shareUri =
              FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                cacheFile,
              )

            val shareIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, scriptName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }

            context.startActivity(Intent.createChooser(shareIntent, "Share script"))
          } else {
            Toast.makeText(context, "Script file not found", Toast.LENGTH_SHORT).show()
          }
        }
      }.onFailure { error ->
        Toast.makeText(context, "Error sharing script: ${error.message}", Toast.LENGTH_LONG).show()
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "Lua Scripts",
              style = MaterialTheme.typography.headlineSmall,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
              )
            }
          },
        )
      },
      floatingActionButton = {
        FloatingActionButton(
          onClick = { backStack.add(LuaScriptEditorScreen(scriptName = null)) },
          containerColor = MaterialTheme.colorScheme.primary,
        ) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Create new script",
            tint = MaterialTheme.colorScheme.onPrimary,
          )
        }
      },
    ) { padding ->
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(
          start = MaterialTheme.spacing.medium,
          top = MaterialTheme.spacing.medium,
          end = MaterialTheme.spacing.medium,
          // Extra clearance so the FAB doesn't overlap the last item
          bottom = MaterialTheme.spacing.medium + 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        item {
          LuaRuntimeStatusCard(
            enabled = enableLuaScripts,
            hasStorageLocation = mpvConfStorageLocation.isNotBlank(),
            enabledScriptsCount = enabledScriptsCount,
            availableScriptsCount = catalog.availableScripts.size,
            onEnabledChange = preferences.enableLuaScripts::set,
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
                title = "No MPV folder selected",
                summary = "Choose an MPV config folder in Advanced settings to browse and manage scripts.",
              )
            }
          }
          catalog.availableScripts.isEmpty() -> {
            item {
              LuaScriptsEmptyState(
                title = "No scripts found",
                summary = "Put your .lua or .js files inside the MPV scripts folder to manage them here.",
              )
            }
          }
          else -> {
            items(catalog.availableScripts) { scriptName ->
              LuaScriptToggleCard(
                scriptName = scriptName,
                selected = selectedScripts.contains(scriptName),
                controlsEnabled = scriptsListEnabled,
                onToggle = { toggleScriptSelection(scriptName) },
                trailingContent = {
                  IconButton(onClick = { shareScript(scriptName) }) {
                    Icon(
                      imageVector = Icons.Default.Share,
                      contentDescription = "Share",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(onClick = { backStack.add(LuaScriptEditorScreen(scriptName = scriptName)) }) {
                    Icon(
                      imageVector = Icons.Default.Edit,
                      contentDescription = "Edit",
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

