package app.gyrolet.mpvrx.ui.tv

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

internal object TvDocumentUris {
  private data class StorageRoot(val id: String, val directory: File)

  fun authority(context: Context): String = "${context.packageName}.tvdocuments"

  fun documentUri(context: Context, file: File): Uri =
    DocumentsContract.buildDocumentUri(authority(context), documentId(context, file))

  fun documentUris(context: Context, files: List<File>): List<Uri> {
    val storageRoots = roots(context)
    return files.map { file -> DocumentsContract.buildDocumentUri(authority(context), documentId(file, storageRoots)) }
  }

  fun treeUri(context: Context, directory: File): Uri =
    DocumentsContract.buildTreeDocumentUri(authority(context), documentId(context, directory))

  fun documentId(context: Context, file: File): String = documentId(file, roots(context))

  fun forEachDocument(context: Context, files: Array<File>, block: (File, String) -> Unit) {
    val storageRoots = roots(context)
    files.forEach { file ->
      val id = runCatching { documentId(file, storageRoots) }.getOrNull() ?: return@forEach
      block(file, id)
    }
  }

  private fun documentId(file: File, storageRoots: List<StorageRoot>): String {
    val target = file.canonicalFile
    val root = storageRoots.sortedByDescending { it.directory.path.length }
      .firstOrNull { contains(it.directory, target) }
      ?: throw FileNotFoundException("File is outside mounted storage")
    val relative = if (target == root.directory) "" else target.relativeTo(root.directory).path
    return "${root.id}:$relative"
  }

  fun file(context: Context, documentId: String): File {
    val separator = documentId.indexOf(':')
    if (separator <= 0) throw FileNotFoundException("Invalid document ID")
    val root = roots(context).firstOrNull { it.id.equals(documentId.substring(0, separator), true) }
      ?: throw FileNotFoundException("Storage is unavailable")
    val target = File(root.directory, documentId.substring(separator + 1)).canonicalFile
    if (!contains(root.directory, target)) throw FileNotFoundException("Invalid document path")
    return target
  }

  private fun contains(root: File, target: File): Boolean =
    target == root || target.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)

  private fun roots(context: Context): List<StorageRoot> {
    val primary = Environment.getExternalStorageDirectory().canonicalFile
    return buildList {
      add(StorageRoot("primary", primary))
      StorageVolumeUtils.getAllStorageVolumes(context).forEach { volume ->
        if (!volume.isPrimary) {
          val path = StorageVolumeUtils.getVolumePath(volume) ?: return@forEach
          val root = runCatching { File(path).canonicalFile }.getOrNull() ?: return@forEach
          val id = volume.uuid?.takeIf(String::isNotBlank) ?: root.name
          if (id.isNotBlank() && ':' !in id) add(StorageRoot(id, root))
        }
      }
    }.distinctBy { it.id.lowercase(Locale.ROOT) }
  }
}

class TvDocumentsProvider : DocumentsProvider() {
  override fun onCreate(): Boolean = true

  override fun queryRoots(projection: Array<out String>?): Cursor =
    MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

  override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
    val file = existingFile(documentId)
    return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, file) }
  }

  override fun queryChildDocuments(
    parentDocumentId: String,
    projection: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    val parent = existingFile(parentDocumentId)
    val children = parent.listFiles() ?: throw FileNotFoundException("Folder is not accessible")
    return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
      TvDocumentUris.forEachDocument(requireProviderContext(), children) { child, id ->
        runCatching { addDocument(this, child, id) }
      }
    }
  }

  override fun getDocumentType(documentId: String): String = mimeType(existingFile(documentId))

  override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
    signal?.throwIfCanceled()
    val file = existingFile(documentId)
    if (!file.isFile) throw FileNotFoundException("Not a file")
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
  }

  override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
    val parent = existingFile(parentDocumentId)
    if (!parent.isDirectory) throw FileNotFoundException("Not a folder")
    val child = childFile(parent, displayName)
    val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
    if (!created) throw FileNotFoundException("Could not create document")
    return TvDocumentUris.documentId(requireProviderContext(), child)
  }

  override fun deleteDocument(documentId: String) {
    if (documentId.substringAfter(':', "").isEmpty()) throw FileNotFoundException("Cannot delete storage")
    if (!existingFile(documentId).delete()) throw FileNotFoundException("Could not delete document")
  }

  override fun renameDocument(documentId: String, displayName: String): String {
    if (documentId.substringAfter(':', "").isEmpty()) throw FileNotFoundException("Cannot rename storage")
    val file = existingFile(documentId)
    val parent = file.parentFile ?: throw FileNotFoundException("Cannot rename storage")
    val target = childFile(parent, displayName)
    if (target.exists() || !file.renameTo(target)) throw FileNotFoundException("Could not rename document")
    return TvDocumentUris.documentId(requireProviderContext(), target)
  }

  override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
    val parent = TvDocumentUris.file(requireProviderContext(), parentDocumentId)
    val child = TvDocumentUris.file(requireProviderContext(), documentId)
    return child.path.startsWith(parent.path.trimEnd(File.separatorChar) + File.separator)
  }

  private fun existingFile(documentId: String): File =
    TvDocumentUris.file(requireProviderContext(), documentId).takeIf(File::exists)
      ?: throw FileNotFoundException("Document is unavailable")

  private fun requireProviderContext(): Context = context ?: throw FileNotFoundException("Provider is unavailable")

  private fun childFile(parent: File, name: String): File {
    if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) {
      throw FileNotFoundException("Invalid file name")
    }
    val child = File(parent, name).canonicalFile
    if (child.parentFile != parent.canonicalFile) throw FileNotFoundException("Invalid file name")
    return child
  }

  private fun addDocument(
    cursor: MatrixCursor,
    file: File,
    documentId: String = TvDocumentUris.documentId(requireProviderContext(), file),
  ) {
    val writable = file.canWrite()
    val flags =
      if (!writable) 0 else if (file.isDirectory) {
        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
          (if (documentId.substringAfter(':').isEmpty()) 0 else DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
      } else {
        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
          DocumentsContract.Document.FLAG_SUPPORTS_RENAME
      }
    val values = cursor.columnNames.map { column ->
      when (column) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name.ifBlank { file.path }
        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType(file)
        DocumentsContract.Document.COLUMN_FLAGS -> flags
        DocumentsContract.Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
        else -> null
      }
    }
    cursor.addRow(values)
  }

  private fun mimeType(file: File): String =
    if (file.isDirectory) {
      DocumentsContract.Document.MIME_TYPE_DIR
    } else {
      val extension = file.extension.lowercase(Locale.ROOT)
      when {
        extension == "iso" -> FileTypeUtils.getMimeTypeFromExtension(extension)
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
          ?: when (extension) {
            in FileTypeUtils.AUDIO_EXTENSIONS ->
              FileTypeUtils.getMimeTypeFromExtension(extension).takeUnless { it == "video/*" } ?: "audio/*"
            in FileTypeUtils.VIDEO_EXTENSIONS -> FileTypeUtils.getMimeTypeFromExtension(extension)
            else -> "application/octet-stream"
          }
      }
    }

  companion object {
    private val DOCUMENT_COLUMNS = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_FLAGS,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
  }
}