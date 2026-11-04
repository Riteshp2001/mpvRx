package app.gyrolet.mpvrx.utils.storage

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import app.gyrolet.mpvrx.utils.media.isPathBasedDocumentAuthority
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

internal data class LocalPlaylistFile(
  val uri: Uri,
  val modifiedAt: Long,
  val size: Long,
)

internal object LocalPlaylistScanner {
  private val extensions = setOf("m3u", "m3u8")
  private val localDocumentProviders = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
  )

  fun changes(context: Context): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        trySend(Unit)
      }
    }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MEDIA_MOUNTED) trySend(Unit)
      }
    }
    val observingFiles = runCatching {
      context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
    }.isSuccess
    val observingMounts = runCatching {
      ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") },
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
    }.isSuccess
    awaitClose {
      if (observingFiles) context.contentResolver.unregisterContentObserver(observer)
      if (observingMounts) context.unregisterReceiver(receiver)
    }
  }

  fun sourceKey(context: Context, uri: Uri): String {
    val path = runCatching { uri.resolveLocalPath(context) }.getOrNull()
    if (!path.isNullOrBlank()) {
      return "file:${runCatching { File(path).canonicalPath }.getOrDefault(path)}"
    }
    if (DocumentsContract.isDocumentUri(context, uri)) {
      return "document:${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    }
    return uri.normalizeScheme().toString()
  }

  suspend fun scan(context: Context, onFile: suspend (LocalPlaylistFile) -> Boolean) = withContext(Dispatchers.IO) {
    val visitedSources = mutableSetOf<String>()
    val accept: suspend (LocalPlaylistFile) -> Unit = { file ->
      currentCoroutineContext().ensureActive()
      val key = sourceKey(context, file.uri)
      if (key !in visitedSources && onFile(file)) visitedSources.add(key)
    }
    scanStorage(context, accept)
    scanMediaStore(context, accept)
    scanGrantedFolders(context, accept)
  }

  private suspend fun scanStorage(context: Context, onFile: suspend (LocalPlaylistFile) -> Unit) {
    val roots = StorageVolumeUtils.getAllStorageVolumes(context)
      .mapNotNull(StorageVolumeUtils::getVolumePath).map(::File) + Environment.getExternalStorageDirectory()
    val directories = ArrayDeque<File>()
    roots.forEach { root -> runCatching { root.canonicalFile }.getOrNull()?.let(directories::addLast) }
    val visited = mutableSetOf<String>()
    val cacheDirectories = (context.externalCacheDirs.filterNotNull() + context.cacheDir)
      .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()

    while (directories.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      val directory = directories.removeLast()
      if (!visited.add(directory.absolutePath) || directory.absolutePath in cacheDirectories) continue
      val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
      for (child in children) {
        currentCoroutineContext().ensureActive()
        if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)) continue
        if (child.isDirectory) {
          directories.addLast(child)
        } else if (child.extension.lowercase() in extensions && child.isFile && child.canRead()) {
          onFile(LocalPlaylistFile(Uri.fromFile(child), child.lastModified(), child.length()))
        }
      }
    }
  }

  private suspend fun scanMediaStore(context: Context, onFile: suspend (LocalPlaylistFile) -> Unit) {
    val collection = MediaStore.Files.getContentUri("external")
    val cursor = runCatching {
      context.contentResolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE),
        "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ? OR LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ?",
        arrayOf("%.m3u", "%.m3u8"),
        null,
      )
    }.getOrNull() ?: return
    cursor.use {
      val idColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
      val modifiedColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
      val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
      while (it.moveToNext()) {
        currentCoroutineContext().ensureActive()
        onFile(LocalPlaylistFile(ContentUris.withAppendedId(collection, it.getLong(idColumn)), it.getLong(modifiedColumn) * 1000L, it.getLong(sizeColumn)))
      }
    }
  }

  private suspend fun scanGrantedFolders(context: Context, onFile: suspend (LocalPlaylistFile) -> Unit) {
    val visited = mutableSetOf<String>()
    for (permission in context.contentResolver.persistedUriPermissions) {
      val treeUri = permission.uri
      if (!permission.isReadPermission || !DocumentsContract.isTreeUri(treeUri) ||
        (treeUri.authority !in localDocumentProviders && !isPathBasedDocumentAuthority(treeUri.authority))
      ) continue
      val directories = ArrayDeque<String>()
      directories.addLast(DocumentsContract.getTreeDocumentId(treeUri))
      while (directories.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val documentId = directories.removeLast()
        val directoryKey = "${treeUri.authority}:$documentId"
        if (directoryKey in visited) continue
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = runCatching {
          context.contentResolver.query(
            childrenUri,
            arrayOf(
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_MIME_TYPE,
              DocumentsContract.Document.COLUMN_LAST_MODIFIED,
              DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
          )
        }.getOrNull() ?: continue
        visited.add(directoryKey)
        cursor.use {
          val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
          val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
          val typeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
          val modifiedColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
          val sizeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
          while (it.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val childId = it.getString(idColumn) ?: continue
            if (it.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
              directories.addLast(childId)
            } else if (it.getString(nameColumn)?.substringAfterLast('.')?.lowercase() in extensions) {
              onFile(LocalPlaylistFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId), it.getLong(modifiedColumn), it.getLong(sizeColumn)))
            }
          }
        }
      }
    }
  }
}