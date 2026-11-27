package app.gyrolet.mpvrx.ui.tv

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.ui.browser.dialogs.FilePickerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.utils.permission.PermissionRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TvPickerActivity : AppCompatActivity() {
  private var returningResult = false
  private var storageRefresh by mutableIntStateOf(0)
  private val requestStoragePermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { storageRefresh++ }
  private val systemPicker =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK && result.data != null) {
        setResult(result.resultCode, result.data)
        finish()
      }
    }

  override fun onResume() {
    super.onResume()
    // Files and permissions may have changed while Android's settings or picker was open.
    storageRefresh++
  }

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
    val mimeTypes =
      intent
        .getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        ?.filter(String::isNotBlank)
        ?.takeIf { it.isNotEmpty() } ?: listOf(intent.type ?: "*/*")
    val initialFolder =
      if (isTree || isCreate) {
        initialPath() ?: if (BuildConfig.SCOPED_STORAGE_ONLY) {
          getExternalFilesDir(null)?.absolutePath ?: Environment.getExternalStorageDirectory().absolutePath
        } else {
          Environment.getExternalStorageDirectory().absolutePath
        }
      } else {
        null
      }
    val suggestedName =
      intent
        .getStringExtra(Intent.EXTRA_TITLE)
        .orEmpty()
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
        } else if (initialFolder != null) {
          FolderPickerDialog(
            isOpen = true,
            currentPath = initialFolder,
            onDismiss = ::finish,
            onFolderSelected = { path ->
              if (isCreate) destination = path else finishWithDocuments(listOf(File(path)), tree = true)
            },
            allowCurrentFolder = true,
            useSystemPickerOnTv = false,
          )
        } else {
          TvFilePicker(mimeTypes, multiple)
        }
      }
    }
  }

  @Composable
  private fun TvFilePicker(
    mimeTypes: List<String>,
    multiple: Boolean,
  ) {
    val extensions = remember { intent.getStringArrayExtra(EXTRA_EXTENSIONS)?.toList().orEmpty() }
    val systemIntent =
      remember {
        Intent(intent).setComponent(null).apply {
          removeExtra(EXTRA_EXTENSIONS)
          removeExtra(EXTRA_INITIAL_PATH)
          removeExtra(EXTRA_MATCH_TO_NAME)
        }
      }
    val refresh = storageRefresh
    val snapshot by produceState<FilePickerSnapshot?>(null, refresh) {
      val previous = value
      value =
        withContext(Dispatchers.IO) {
          val storage = tvPickerStorage(this@TvPickerActivity)
          val initialPath =
            if (previous != null) {
              previous.initialPath
            } else {
              initialPath() ?: intent
                .getStringExtra(EXTRA_INITIAL_PATH)
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?.absolutePath
            }
          FilePickerSnapshot(initialPath, storage)
        }
    }
    val (path, storage) =
      snapshot ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
      }
    val needsAllFilesAccess =
      remember(refresh) {
        PermissionRecovery.usesAllFilesAccess() && !PermissionRecovery.isStorageGranted(this)
      }
    val missingPermissions =
      remember(refresh) {
        storagePermissions(mimeTypes, extensions.isNotEmpty()).filter {
          ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
      }
    val onOpenSystemPicker: (() -> Unit)? =
      remember(refresh) {
        if (systemIntent.resolveActivity(packageManager) == null) null else ({ openSystemPicker(systemIntent) })
      }
    val requestStorageAccess: (() -> Unit)? =
      when {
        needsAllFilesAccess -> ::openStorageSettings
        missingPermissions.isNotEmpty() -> ({ requestStoragePermissions.launch(missingPermissions.toTypedArray()) })
        else -> null
      }
    FilePickerDialog(
      isOpen = true,
      currentPath = path,
      onDismiss = ::finish,
      onFileSelected = { file -> finishWithDocuments(listOf(File(file)), tree = false) },
      onSystemPickerRequest = onOpenSystemPicker,
      title = stringResource(R.string.tv_picker_select_file),
      matchToName = intent.getStringExtra(EXTRA_MATCH_TO_NAME),
      allowedExtensions = extensions,
      allowedMimeTypes = mimeTypes.takeIf { extensions.isEmpty() },
      onFilesSelected =
        if (multiple) {
          { files -> finishWithDocuments(files.map(::File), tree = false) }
        } else {
          null
        },
      tvStorage = storage,
      storageRefresh = refresh,
      onStorageAccessRequest = requestStorageAccess,
      onReturnToStorageLocations = { storageRefresh++ },
    )
  }

  private fun openStorageSettings() {
    if (!PermissionRecovery.openAllFilesAccessSettings(this)) {
      AppToast.warning(this, getString(R.string.no_app_for_action))
    }
  }

  private fun openSystemPicker(systemIntent: Intent) {
    try {
      systemPicker.launch(systemIntent)
    } catch (_: ActivityNotFoundException) {
      AppToast.warning(this, getString(R.string.no_app_for_action))
    } catch (_: SecurityException) {
      AppToast.warning(this, getString(R.string.no_app_for_action))
    }
  }

  @Suppress("DEPRECATION")
  private fun initialPath(): String? {
    val uri = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI) ?: return null
    if (uri.authority != TvDocumentUris.authority(this) && uri.authority != "com.android.externalstorage.documents") {
      return null
    }
    return runCatching {
      val id =
        if (DocumentsContract.isTreeUri(uri)) {
          DocumentsContract.getTreeDocumentId(uri)
        } else {
          DocumentsContract.getDocumentId(uri)
        }
      val file = TvDocumentUris.file(this, id)
      val directory = if (file.isDirectory) file else file.parentFile?.takeIf(File::isDirectory)
      directory?.absolutePath
    }.getOrNull()
  }

  private fun finishWithDocuments(
    files: List<File>,
    tree: Boolean,
  ) {
    if (returningResult) return
    returningResult = true
    lifecycleScope.launch {
      val result =
        withContext(Dispatchers.IO) {
          runCatching { buildTvPickerResult(this@TvPickerActivity, files, tree) }
        }
      returningResult = false
      result
        .onSuccess {
          setResult(Activity.RESULT_OK, it)
          finish()
        }.onFailure { error ->
          val message = if (error is SecurityException) R.string.tv_picker_no_access else R.string.generic_unknown_error
          AppToast.warning(this@TvPickerActivity, getString(message))
        }
    }
  }

  companion object {
    internal const val EXTRA_EXTENSIONS = "app.gyrolet.mpvrx.tv.EXTENSIONS"
    internal const val EXTRA_INITIAL_PATH = "app.gyrolet.mpvrx.tv.INITIAL_PATH"
    internal const val EXTRA_MATCH_TO_NAME = "app.gyrolet.mpvrx.tv.MATCH_TO_NAME"
    internal val FILE_ACTIONS = setOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)
    internal val SUPPORTED_ACTIONS =
      FILE_ACTIONS + setOf(Intent.ACTION_OPEN_DOCUMENT_TREE, Intent.ACTION_CREATE_DOCUMENT)
  }
}

private data class FilePickerSnapshot(
  val initialPath: String?,
  val storage: TvPickerStorage,
)

private fun storagePermissions(
  mimeTypes: List<String>,
  hasExtensionFilter: Boolean,
): List<String> =
  when {
    PermissionRecovery.usesAllFilesAccess() -> emptyList()
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else ->
      buildList {
        val acceptsAnyMedia = !hasExtensionFilter && "*/*" in mimeTypes
        if (acceptsAnyMedia || mimeTypes.any { it.startsWith("video/", ignoreCase = true) }) {
          add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (acceptsAnyMedia || mimeTypes.any { it.startsWith("audio/", ignoreCase = true) }) {
          add(Manifest.permission.READ_MEDIA_AUDIO)
        }
      }
  }

internal fun buildTvPickerResult(
  context: Context,
  files: List<File>,
  tree: Boolean,
): Intent {
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
      clipData =
        ClipData.newRawUri("documents", uris.first()).apply {
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
          onValueChange = {
            name = it
            error = null
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.ui_name)) },
          singleLine = true,
          isError = error != null,
          supportingText = error?.let { message -> { Text(message) } },
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = !saving,
        onClick = {
          val filename = name.trim()
          if (filename.isBlank() ||
            filename == "." ||
            filename == ".." ||
            '/' in filename ||
            '\\' in filename ||
            '\u0000' in filename
          ) {
            error = context.getString(R.string.pref_folders_marker_invalid)
          } else {
            saving = true
            scope.launch {
              val result =
                withContext(Dispatchers.IO) {
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
                error =
                  if (failure is java.nio.file.FileAlreadyExistsException) {
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
