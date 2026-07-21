package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.R

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.editor.MpvHelpScreen
import app.gyrolet.mpvrx.ui.editor.MpvScriptEditor
import app.gyrolet.mpvrx.ui.player.MpvConfigSanitizer
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.io.path.readLines

@Serializable
data class ConfigEditorScreen(
  val configType: ConfigType
) : Screen {

  enum class ConfigType {
    MPV_CONF,
    INPUT_CONF
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context      = LocalContext.current
    val backStack    = LocalBackStack.current
    val preferences  = koinInject<AdvancedPreferences>()
    val scope        = rememberCoroutineScope()

    val (fileName, initialValue) = when (configType) {
      ConfigType.MPV_CONF   -> "mpv.conf"   to preferences.mpvConf.get()
      ConfigType.INPUT_CONF -> "input.conf" to preferences.inputConf.get()
    }
    val screenTitle = when (configType) {
      ConfigType.MPV_CONF   -> "Edit mpv.conf"
      ConfigType.INPUT_CONF -> "Edit input.conf"
    }
    val editorLanguage = when (configType) {
      ConfigType.MPV_CONF   -> "mpv.conf"
      ConfigType.INPUT_CONF -> "input.conf"
    }

    var configText       by remember { mutableStateOf(initialValue) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()

    // Load from external storage if a folder is configured
    LaunchedEffect(mpvConfStorageLocation) {
      if (mpvConfStorageLocation.isBlank()) return@LaunchedEffect
      withContext(Dispatchers.IO) {
        val tempFile = createTempFile()
        runCatching {
          val tree       = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
          val configFile = tree?.findFile(fileName)
          if (configFile != null && configFile.exists()) {
            context.contentResolver.openInputStream(configFile.uri)?.use { input ->
              tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val content = tempFile.readLines().joinToString("\n")
            withContext(Dispatchers.Main) { configText = content }
          }
        }
        tempFile.deleteIfExists()
      }
    }

    fun saveConfig() {
      scope.launch(Dispatchers.IO) {
        try {
          val validation =
            if (configType == ConfigType.MPV_CONF) MpvConfigSanitizer.sanitize(configText)
            else MpvConfigSanitizer.Result(configText, emptyList())
          val contentToSave = validation.content
          when (configType) {
            ConfigType.MPV_CONF   -> preferences.mpvConf.set(contentToSave)
            ConfigType.INPUT_CONF -> preferences.inputConf.set(contentToSave)
          }
          File(context.filesDir, fileName).writeText(contentToSave)

          if (mpvConfStorageLocation.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
            if (tree == null) {
              withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_no_storage_location_set), Toast.LENGTH_LONG).show()
              }
              return@launch
            }
            val existing = tree.findFile(fileName)
            val confFile = existing ?: tree.createFile("text/plain", fileName)?.also { it.renameTo(fileName) }
            val uri = confFile?.uri ?: run {
              withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_failed_to_create_file), Toast.LENGTH_LONG).show()
              }
              return@launch
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
              out.write(contentToSave.toByteArray())
              out.flush()
            }
          }

          withContext(Dispatchers.Main) {
            configText = contentToSave
            hasUnsavedChanges = false
            val message =
              if (validation.warnings.isEmpty()) "$fileName saved"
              else "$fileName saved; ${validation.warnings.size} incompatible option(s) disabled"
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            backStack.popSafely()
          }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.toast_failed_to_save_reason, e.message ?: context.getString(R.string.generic_unknown_error)), Toast.LENGTH_LONG).show()
          }
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxSize()
    ) {
      // Fixed TopAppBar
      TopAppBar(
        title = {
          Column {
            Text(
              text  = screenTitle,
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
            if (hasUnsavedChanges) {
              Text(text  = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_unsaved_changes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = { backStack.popSafely() }) {
            Icon(
              Icons.RoundedFilled.ArrowBack,
              contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.back),
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        },
        actions = {
          if (configType == ConfigType.MPV_CONF) {
            IconButton(
              onClick = {
                val sanitized = MpvConfigSanitizer.sanitize(configText)
                configText = sanitized.content
                hasUnsavedChanges = hasUnsavedChanges || sanitized.warnings.isNotEmpty()
                val message =
                  if (sanitized.warnings.isEmpty()) "No known incompatible options found"
                  else "Disabled ${sanitized.warnings.size} incompatible option(s)"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
              },
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Clear,
                contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_reset_incompatible_configuration),
              )
            }
          }
          IconButton(
            onClick = {
              backStack.add(MpvHelpScreen())
            },
            modifier = Modifier.padding(end = 4.dp).size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
              contentColor = MaterialTheme.colorScheme.secondary,
            ),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Info,
              contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_help),
            )
          }
          IconButton(
            onClick  = { saveConfig() },
            enabled  = hasUnsavedChanges,
            modifier = Modifier.padding(horizontal = 12.dp).size(40.dp),
            colors   = IconButtonDefaults.iconButtonColors(
              containerColor        = if (hasUnsavedChanges) MaterialTheme.colorScheme.primaryContainer
                                      else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
              contentColor          = if (hasUnsavedChanges) MaterialTheme.colorScheme.onPrimaryContainer
                                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
              disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
              disabledContentColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
            shape = RoundedCornerShape(8.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_save),
            )
          }
        },
      )
      
      // Editor content with IME padding
      Box(
        modifier = Modifier
          .fillMaxSize()
          .weight(1f)
          .imePadding()
      ) {
        MpvScriptEditor(
          content = configText,
          onContentChange = {
            configText = it
            hasUnsavedChanges = true
          },
          language = editorLanguage,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

