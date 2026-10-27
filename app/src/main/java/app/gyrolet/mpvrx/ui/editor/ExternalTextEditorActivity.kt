/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Android's Open-with entry point for mpvRx-specific configs and text scripts.
 * It intentionally does not route documents through PlayerActivity or execute imported code.
 */
package app.gyrolet.mpvrx.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.ui.player.MpvConfigCache
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Restrict file-open intents to URI grants and recognized text/config formats. The UI offers
 * a separate, explicit Import action for mpv.conf/input.conf: simply opening an external file
 * never replaces the active app configuration.
 */
class ExternalTextEditorActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val sourceIntent = intent ?: run { finish(); return }
    val source = sourceIntent.data ?: run { finish(); return }
    if (sourceIntent.action != Intent.ACTION_VIEW && sourceIntent.action != Intent.ACTION_EDIT) {
      finish()
      return
    }
    if (source.scheme != "content" && source.scheme != "file") {
      finish()
      return
    }
    val fileName = resolveFileName(this, source)
    if (!isSupportedTextFile(fileName)) {
      Toast.makeText(this, "This file is not a supported text/config document", Toast.LENGTH_LONG).show()
      finish()
      return
    }
    val canWrite = (sourceIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0 ||
      source.scheme == "file"
    setContent {
      MpvrxTheme {
        ExternalTextEditorScreen(
          uri = source,
          fileName = fileName,
          canWriteOriginal = canWrite,
          onClose = { finish() },
        )
      }
    }
  }

  companion object {
    private val allowedExtensions =
      setOf("conf", "cfg", "ini", "properties", "xml", "json", "lua", "js", "txt", "yaml", "yml")

    fun isSupportedTextFile(name: String): Boolean {
      val suffix = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
      return suffix in allowedExtensions
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
      if (uri.scheme == "content") {
        runCatching {
          context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
              if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
              } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
      }
      return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "unknown"
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalTextEditorScreen(
  uri: Uri,
  fileName: String,
  canWriteOriginal: Boolean,
  onClose: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val scope = rememberCoroutineScope()
  val preferences = koinInject<AdvancedPreferences>()
  val mpvConfigCache = koinInject<MpvConfigCache>()
  var text by remember(uri) { mutableStateOf("") }
  var loaded by remember(uri) { mutableStateOf(false) }
  var dirty by remember(uri) { mutableStateOf(false) }
  var loadError by remember(uri) { mutableStateOf<String?>(null) }
  var busy by remember(uri) { mutableStateOf(false) }
  var showDiscard by remember { mutableStateOf(false) }
  var showImport by remember { mutableStateOf(false) }
  val fileKind = fileName.lowercase(Locale.ROOT)
  val isMpvConfig = fileKind == "mpv.conf"
  val isInputConfig = fileKind == "input.conf"

  fun requestClose() {
    if (dirty) showDiscard = true else onClose()
  }
  BackHandler { requestClose() }

  LaunchedEffect(uri) {
    loadError = null
    try {
      val contents = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
          val buffer = CharArray(4096)
          val builder = StringBuilder()
          while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (builder.length + count > 1_000_000) {
              error("This text document is larger than the 1 MB editor safety limit")
            }
            builder.append(buffer, 0, count)
          }
          builder.toString()
        } ?: error("Unable to read document")
      }
      if (!dirty) text = contents
      loaded = true
    } catch (e: Exception) {
      loadError = e.message ?: "Unable to open document"
    }
  }

  fun writeDocument(destination: Uri, closeOnSuccess: Boolean) {
    if (busy) return
    busy = true
    val savedText = text
    scope.launch {
      try {
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(destination, "wt")
            ?.use { output -> output.write(savedText.toByteArray(Charsets.UTF_8)) }
            ?: error("The document provider does not permit writing")
        }
        if (text == savedText) dirty = false
        Toast.success(context, "Saved $fileName")
        if (closeOnSuccess && !dirty) onClose()
      } catch (e: Exception) {
        Toast.error(context, "Save failed: ${e.message}")
      } finally {
        busy = false
      }
    }
  }

  val createCopy = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("text/plain"),
  ) { destination ->
    if (destination != null) writeDocument(destination, closeOnSuccess = false)
  }

  if (showDiscard) {
    AlertDialog(
      onDismissRequest = { showDiscard = false },
      title = { Text("Discard your changes?") },
      text = { Text("Unsaved edits to $fileName will be lost.") },
      confirmButton = {
        TextButton(onClick = { showDiscard = false; onClose() }) { Text("Discard") }
      },
      dismissButton = {
        TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
      },
    )
  }

  if (showImport) {
    AlertDialog(
      onDismissRequest = { showImport = false },
      title = { Text("Import $fileName into mpvRx?") },
      text = { Text("This replaces the currently active $fileName configuration inside mpvRx.") },
      confirmButton = {
        TextButton(onClick = {
          showImport = false
          val importedText = text
          scope.launch {
            try {
              withContext(Dispatchers.IO) {
                if (isMpvConfig) {
                  mpvConfigCache.update(importedText)
                } else if (isInputConfig) {
                  preferences.inputConf.set(importedText)
                  java.io.File(context.filesDir, "input.conf").writeText(importedText)
                }
              }
              Toast.success(context, "Imported $fileName")
              onClose()
            } catch (e: Exception) {
              Toast.error(context, "Import failed: ${e.message}")
            }
          }
        }) { Text("Import") }
      },
      dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } },
    )
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(fileName, maxLines = 1) },
        navigationIcon = { TextButton(onClick = ::requestClose) { Text("Back") } },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when {
        loadError != null -> Text(
          loadError.orEmpty(),
          modifier = Modifier.padding(16.dp),
          color = MaterialTheme.colorScheme.error,
        )
        !loaded -> CircularProgressIndicator(Modifier.padding(20.dp))
        else -> {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              enabled = dirty && canWriteOriginal && !busy,
              onClick = { writeDocument(uri, closeOnSuccess = false) },
            ) { Text("Save") }
            OutlinedButton(enabled = !busy, onClick = { createCopy.launch(fileName) }) {
              Text("Save as…")
            }
            if (isMpvConfig || isInputConfig) {
              OutlinedButton(enabled = !busy, onClick = { showImport = true }) {
                Text("Import to mpvRx")
              }
            }
          }
          MpvScriptEditor(
            content = text,
            onContentChange = { changed -> text = changed; dirty = true },
            language = when {
              isInputConfig -> "input.conf"
              isMpvConfig || fileKind.endsWith(".conf") || fileKind.endsWith(".cfg") ||
                fileKind.endsWith(".ini") -> "mpv.conf"
              fileKind.endsWith(".xml") -> "xml"
              fileKind.endsWith(".json") -> "json"
              fileKind.endsWith(".lua") -> "lua"
              fileKind.endsWith(".js") -> "js"
              else -> "text"
            },
            modifier = Modifier.weight(1f),
          )
          if (dirty) {
            Text(
              "Unsaved changes",
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
              color = MaterialTheme.colorScheme.secondary,
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }
    }
  }
}
