package app.gyrolet.mpvrx.repository.subtitle

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.utils.media.ChecksumUtils
import app.gyrolet.mpvrx.utils.media.resolveSubtitleStorageDirectory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class OnlineSubtitleFileStore(
  private val context: Context,
  private val preferences: SubtitlesPreferences,
) {
  fun save(
    bytes: ByteArray,
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Uri {
    val extracted = extractFirstSubtitle(bytes)
    val payload = extracted?.bytes ?: bytes
    val extension =
      extracted?.extension
        ?: subtitle.format?.lowercase()
        ?: extensionFromName(subtitle.fileName)
        ?: extensionFromName(subtitle.url)
        ?: "srt"

    val saveFolderUri = preferences.subtitleSaveFolder.get()
    val folderName = ChecksumUtils.getCRC32(mediaTitle)
    val fullTitle = mediaTitle.substringBeforeLast(".")
    val langCode = (subtitle.language ?: "und").sanitizeFilePart()
    val subFileName = "${fullTitle.sanitizeFilePart()}.$langCode.$extension"

    if (saveFolderUri.isNotBlank()) {
      val parentDir = resolveSubtitleStorageDirectory(context, saveFolderUri, createIfMissing = true)
      if (parentDir?.exists() == true) {
        val movieDir = parentDir.findFile(folderName) ?: parentDir.createDirectory(folderName)
        if (movieDir != null) {
          val subFile = movieDir.findFile(subFileName) ?: movieDir.createFile("application/octet-stream", subFileName)
          if (subFile != null) {
            context.contentResolver.openOutputStream(subFile.uri)?.use { it.write(payload) }
            return subFile.uri
          }
        }
      }
    }

    val internalMoviesDir = File(context.getExternalFilesDir(null), "Movies")
    val movieDir = File(internalMoviesDir, folderName).apply { if (!exists()) mkdirs() }
    val file = File(movieDir, subFileName)
    FileOutputStream(file).use { it.write(payload) }
    return Uri.fromFile(file)
  }

  fun delete(uri: Uri): Boolean {
    val file =
      if (uri.scheme == "content") {
        DocumentFile.fromSingleUri(context, uri)
      } else {
        DocumentFile.fromFile(File(uri.path ?: uri.toString()))
      }
    return file?.takeIf { it.exists() }?.delete() == true
  }

  private fun extractFirstSubtitle(bytes: ByteArray): ExtractedSubtitle? {
    if (!looksLikeZip(bytes)) return null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: return null
        if (entry.isDirectory) continue
        val extension = extensionFromName(entry.name) ?: continue
        if (extension !in SUBTITLE_EXTENSIONS) continue
        return ExtractedSubtitle(
          bytes = zip.readBytes(),
          extension = extension,
        )
      }
    }
  }

  private fun looksLikeZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 'P'.code.toByte() &&
      bytes[1] == 'K'.code.toByte() &&
      (
        bytes[2] == 3.toByte() ||
          bytes[2] == 5.toByte() ||
          bytes[2] == 7.toByte()
      )

  private fun extensionFromName(value: String?): String? =
    value
      ?.substringBefore("?")
      ?.substringAfterLast("/", "")
      ?.substringAfterLast(".", "")
      ?.lowercase()
      ?.takeIf { it.isNotBlank() && it.length <= 5 }

  private fun String.sanitizeFilePart(): String =
    replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "subtitle" }

  private data class ExtractedSubtitle(
    val bytes: ByteArray,
    val extension: String,
  )

  private companion object {
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")
  }
}
