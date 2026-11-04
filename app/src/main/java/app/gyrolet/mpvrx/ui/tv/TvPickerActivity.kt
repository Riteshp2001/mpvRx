package app.gyrolet.mpvrx.ui.tv

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.ui.browser.dialogs.FilePickerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvPickerActivity : AppCompatActivity() {
  private var returningResult = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val action = intent.action
    if (action !in SUPPORTED_ACTIONS) {
      finish()
      return
    }
    val isTree = action == Intent.ACTION_OPEN_DOCUMENT_TREE
    val isCreate = action == Intent.ACTION_CREATE_DOCUMENT
    val multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
    val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.filter(String::isNotBlank)
      ?.takeIf { it.isNotEmpty() } ?: listOf(intent.type ?: "*/*")
    val initialPath = initialPath()
    val suggestedName = intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()
      .ifBlank { getString(R.string.app_name) }

    setContent {
      MpvrxTheme {
        var destination by rememberSaveable { mutableStateOf<String?>(null) }
        if (isCreate && destination != null) {
          SaveDocumentDialog(
            directory = destination!!,
            suggestedName = suggestedName,
            onDismiss = { destination = null },
            onSaved = { file -> finishWithDocuments(listOf(file), tree = false) },
          )
        } else if (isTree || isCreate) {
          FolderPickerDialog(
            isOpen = true,
            currentPath = initialPath,
            onDismiss = ::finish,
            onFolderSelected = { path ->
              if (isCreate) destination = path else finishWithDocuments(listOf(File(path)), tree = true)
            },
            allowCurrentFolder = true,
            useSystemPickerOnTv = false,
          )
        } else {
          FilePickerDialog(
            isOpen = true,
            currentPath = initialPath,
            onDismiss = ::finish,
            onFileSelected = { path -> finishWithDocuments(listOf(File(path)), tree = false) },
            onSystemPickerRequest = null,
            title = stringResource(R.string.ui_select),
            allowedMimeTypes = mimeTypes,
            onFilesSelected = if (multiple) {
              { paths -> finishWithDocuments(paths.map(::File), tree = false) }
            } else {
              null
            },
          )
        }
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun initialPath(): String {
    val uri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
    val selected = runCatching {
      if (uri == null) return@runCatching null
      if (uri.authority != TvDocumentUris.authority(this) && uri.authority != "com.android.externalstorage.documents") {
        return@runCatching null
      }
      val id = if (DocumentsContract.isTreeUri(uri)) {
        DocumentsContract.getTreeDocumentId(uri)
      } else {
        DocumentsContract.getDocumentId(uri)
      }
      TvDocumentUris.file(this, id).let { file -> if (file.isDirectory) file else file.parentFile }
    }.getOrNull()
    return selected?.absolutePath ?: if (BuildConfig.SCOPED_STORAGE_ONLY) {
      getExternalFilesDir(null)?.absolutePath ?: Environment.getExternalStorageDirectory().absolutePath
    } else {
      Environment.getExternalStorageDirectory().absolutePath
    }
  }

  private fun finishWithDocuments(files: List<File>, tree: Boolean) {
    if (returningResult) return
    returningResult = true
    lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching { buildTvPickerResult(this@TvPickerActivity, files, tree) }
      }
      returningResult = false
      result.onSuccess {
        setResult(Activity.RESULT_OK, it)
        finish()
      }.onFailure { error ->
        val message = if (error is SecurityException) R.string.tv_picker_no_access else R.string.generic_unknown_error
        AppToast.warning(this@TvPickerActivity, getString(message))
      }
    }
  }

  companion object {
    internal val SUPPORTED_ACTIONS = setOf(
      Intent.ACTION_OPEN_DOCUMENT,
      Intent.ACTION_OPEN_DOCUMENT_TREE,
      Intent.ACTION_GET_CONTENT,
      Intent.ACTION_CREATE_DOCUMENT,
    )
  }
}

internal fun buildTvPickerResult(context: Context, files: List<File>, tree: Boolean): Intent {
  require(files.isNotEmpty())
  require(!tree || files.size == 1)
  files.forEach { file ->
    require(if (tree) file.isDirectory else file.isFile)
    if (!file.canRead()) throw SecurityException("Location is not readable")
  }
  val uris =
    if (tree) listOf(TvDocumentUris.treeUri(context, files.single())) else TvDocumentUris.documentUris(context, files)
  return Intent().apply {
    data = uris.first()
    if (uris.size > 1) {
      clipData = ClipData.newRawUri("documents", uris.first()).apply {
        uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
      }
    }
    addFlags(
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
    )
    if (tree) addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
  }
}

@Composable
private fun SaveDocumentDialog(
  directory: String,
  suggestedName: String,
  onDismiss: () -> Unit,
  onSaved: (File) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var name by rememberSaveable(directory, suggestedName) { mutableStateOf(suggestedName) }
  var error by rememberSaveable(directory) { mutableStateOf<String?>(null) }
  var saving by remember(directory) { mutableStateOf(false) }
  AppAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.ui_save)) },
    text = {
      Column {
        AppTextField(
          value = name,
          onValueChange = { name = it; error = null },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.ui_name)) },
          singleLine = true,
          isError = error != null,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !saving,
        onClick = {
          val filename = name.trim()
          if (filename.isBlank() || filename == "." || filename == ".." ||
            '/' in filename || '\\' in filename || '\u0000' in filename
          ) {
            error = context.getString(R.string.pref_folders_marker_invalid)
          } else {
            saving = true
            scope.launch {
              val result = withContext(Dispatchers.IO) {
                runCatching {
                  val parent = File(directory).canonicalFile
                  val file = File(parent, filename).canonicalFile
                  require(file.parentFile == parent)
                  TvDocumentUris.documentId(context, file)
                  if (file.exists() || !file.createNewFile()) throw java.nio.file.FileAlreadyExistsException(filename)
                  file
                }
              }
              saving = false
              result.onSuccess(onSaved).onFailure { failure ->
                error = if (failure is java.nio.file.FileAlreadyExistsException) {
                  context.getString(R.string.tv_picker_file_exists)
                } else {
                  context.getString(R.string.pref_export_failed, failure.localizedMessage.orEmpty())
                }
              }
            }
          }
        },
      ) { Text(stringResource(R.string.ui_save)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) } },
  )
}