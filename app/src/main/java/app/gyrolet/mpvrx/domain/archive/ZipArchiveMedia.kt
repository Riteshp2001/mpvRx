/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.archive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.text.format.Formatter
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.browser.PathComponent
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ZipArchiveMedia {
  private const val BROWSER_SCHEME = "mpvrx-zip"
  private const val BROWSER_AUTHORITY = "local"
  private const val PLAYBACK_SCHEME = "archive"
  private const val MAX_ENTRIES = 100_000

  data class Location(
    val archivePath: String,
    val directory: String,
  )

  private data class PlaybackLocation(
    val archivePath: String,
    val entryPath: String,
  )

  data class PlaylistContent(
    val source: String,
    val name: String,
    val videos: List<Video>,
  )

  data class OpenedPlayback(
    val uri: String,
    val descriptor: ParcelFileDescriptor? = null,
  )

  private data class FolderStats(
    var videoCount: Int = 0,
    var totalSize: Long = 0L,
    var hasSubfolders: Boolean = false,
  )

  private suspend fun resolveZipPath(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
      try {
        val localFile =
          when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let(::File)
            "content" -> runCatching { uri.resolveLocalPath(context) }.getOrNull()?.let(::File)
            else -> null
          }

        val source =
          uri.toString().takeIf { uri.scheme == "content" }
            ?: localFile?.takeIf { it.isFile && it.canRead() }?.absolutePath
            ?: return@withContext null
        readArchive(context, source) { archive ->
          require(archive.size() <= MAX_ENTRIES) { "ZIP archive has too many entries" }
        }
        source
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }
    }

  suspend fun playlistContent(context: Context, uri: Uri): PlaylistContent = withContext(Dispatchers.IO) {
    val source = resolveZipPath(context, uri) ?: throw IOException("Cannot open a seekable ZIP source")
    val videos = allMedia(context, browserPath(source), includeAudio = true).getOrThrow()
      .distinctBy(Video::path)
    currentCoroutineContext().ensureActive()
    require(videos.isNotEmpty()) { "ZIP archive contains no playable media" }
    PlaylistContent(source, archiveDisplayName(context, uri, File(source).takeIf(File::isAbsolute)), videos)
  }

  private fun normalizeArchiveSource(source: String): String? =
    when {
      source.startsWith("content://") -> source
      source.startsWith("file://") -> Uri.parse(source).path?.takeIf { File(it).isAbsolute }
      File(source).isAbsolute -> File(source).absolutePath
      else -> null
    }

  private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("ZIP source is unavailable")
    try {
      Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
      return descriptor
    } catch (error: Exception) {
      descriptor.close()
      throw IOException("ZIP source must support seekable access", error)
    }
  }

  private inline fun <T> readArchive(context: Context, source: String, block: (ZipFile) -> T): T {
    if (source.startsWith("content://")) {
      return openDescriptor(context, Uri.parse(source)).use { descriptor ->
        ZipFile("/proc/self/fd/${descriptor.fd}").use(block)
      }
    }
    return ZipFile(source).use(block)
  }

  fun openPlayback(context: Context, value: String): OpenedPlayback {
    val location = parsePlaybackLocation(value) ?: throw IOException("Invalid ZIP entry")
    if (!location.archivePath.startsWith("content://")) {
      val file = File(location.archivePath)
      if (!file.isFile || !file.canRead()) throw IOException("ZIP source is unavailable")
      return OpenedPlayback(playbackUri(location.archivePath, location.entryPath))
    }
    val descriptor = openDescriptor(context, Uri.parse(location.archivePath))
    return try {
      OpenedPlayback(playbackUri("/proc/self/fd/${descriptor.fd}", location.entryPath), descriptor)
    } catch (error: Exception) {
      descriptor.close()
      throw error
    }
  }

  fun sourceOf(value: String): String? = parsePlaybackLocation(value)?.archivePath

  fun entryPathOf(value: String): String? = parsePlaybackLocation(value)?.entryPath

  fun sourceAvailable(context: Context, source: String): Boolean = runCatching {
    if (source.startsWith("content://")) {
      openDescriptor(context, Uri.parse(source)).use { true }
    } else {
      File(source).let { it.isFile && it.canRead() }
    }
  }.getOrDefault(false)

  fun sourceDeleted(context: Context, source: String): Boolean {
    val mountedStates = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
    if (!source.startsWith("content://")) {
      val file = File(source)
      val appOwned = file.absolutePath.startsWith(context.filesDir.absolutePath + File.separator)
      if (!appOwned && Environment.getExternalStorageState(file) !in mountedStates) return false
      return try {
        Os.stat(file.absolutePath)
        false
      } catch (error: ErrnoException) {
        error.errno == OsConstants.ENOENT
      }
    }

    val uri = Uri.parse(source)
    if (uri.authority == "com.android.externalstorage.documents") {
      val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
      val volumeId = documentId?.substringBefore(':')
      val relativePath = documentId?.substringAfter(':', "")?.let(::normalizeEntryPath)
      if (!relativePath.isNullOrBlank() && volumeId != null) {
        val volume = when {
          volumeId == "primary" -> Environment.getExternalStorageDirectory()
          volumeId.matches(Regex("[A-Za-z0-9-]+")) -> File("/storage/$volumeId")
          else -> null
        }
        if (volume != null) {
          val file = File(volume, relativePath)
          val ancestor = generateSequence(file.parentFile) { it.parentFile }.firstOrNull(File::exists)
          if (ancestor?.canRead() == true && sourceDeleted(context, file.absolutePath)) return true
        }
      }
    }
    if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
      return false
    }
    val volumeFile = when (uri.authority) {
      "com.android.externalstorage.documents" -> {
        val volumeId = runCatching { DocumentsContract.getDocumentId(uri).substringBefore(':') }.getOrNull() ?: return false
        if (volumeId == "primary") Environment.getExternalStorageDirectory()
        else if (volumeId.matches(Regex("[A-Za-z0-9-]+"))) File("/storage/$volumeId")
        else return false
      }
      "com.android.providers.downloads.documents", "com.android.providers.media.documents" -> Environment.getExternalStorageDirectory()
      else -> return false
    }
    if (Environment.getExternalStorageState(volumeFile) !in mountedStates) return false
    return try {
      context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        ?.use { !it.moveToFirst() } ?: false
    } catch (_: FileNotFoundException) {
      true
    } catch (_: Exception) {
      false
    }
  }

  fun clearLegacyCache(context: Context, activeUri: String? = null) {
    val activeSource = activeUri?.let(::sourceOf)
    val roots = listOf(context.cacheDir, context.filesDir) + context.externalCacheDirs.filterNotNull()
    for (root in roots.distinct()) {
      for (name in listOf("zip_archives", "zip_entries", "imported_zip_archives")) {
        val directory = File(root, name)
        if (activeSource?.startsWith(directory.absolutePath + File.separator) == true) continue
        if (directory.exists() && directory.canonicalFile.parentFile == root.canonicalFile) {
          directory.deleteRecursively()
        }
      }
    }
  }

  private fun archiveDisplayName(
    context: Context,
    uri: Uri,
    localFile: File?,
  ): String {
    val providerName = runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
      }
    }.getOrNull()
    val safeName =
      (providerName ?: localFile?.name)
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
        ?.take(120)
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        ?: "archive"
    return if (safeName.endsWith(".zip", ignoreCase = true)) safeName else "$safeName.zip"
  }

  fun browserPath(archivePath: String, directory: String = ""): String {
    val normalizedDirectory = normalizeEntryPath(directory).orEmpty()
    return Uri.Builder()
      .scheme(BROWSER_SCHEME)
      .authority(BROWSER_AUTHORITY)
      .appendQueryParameter("archive", requireNotNull(normalizeArchiveSource(archivePath)))
      .appendQueryParameter("directory", normalizedDirectory)
      .build()
      .toString()
  }

  fun parseBrowserPath(value: String): Location? = runCatching {
    val uri = Uri.parse(value)
    if (!uri.scheme.equals(BROWSER_SCHEME, ignoreCase = true) || uri.authority != BROWSER_AUTHORITY) {
      return@runCatching null
    }
    val archivePath = uri.getQueryParameter("archive")?.let(::normalizeArchiveSource) ?: return@runCatching null
    val directory = normalizeEntryPath(uri.getQueryParameter("directory").orEmpty()) ?: return@runCatching null
    Location(archivePath, directory)
  }.getOrNull()

  fun isBrowserPath(value: String): Boolean = parseBrowserPath(value) != null

  fun isArchiveRoot(value: String): Boolean = parseBrowserPath(value)?.directory?.isEmpty() == true

  fun isPlaybackUri(value: String): Boolean = parsePlaybackLocation(value) != null

  private fun parsePlaybackLocation(value: String): PlaybackLocation? {
    val prefix = "$PLAYBACK_SCHEME://"
    if (!value.startsWith(prefix, ignoreCase = true)) return null
    val encodedLocation = value.substring(prefix.length)
    val separator = encodedLocation.indexOf('|')
    if (separator <= 0 || separator == encodedLocation.lastIndex) return null

    val archivePath = runCatching { Uri.decode(encodedLocation.substring(0, separator)) }.getOrNull()
      ?.let(::normalizeArchiveSource) ?: return null
    val entryPath = normalizeEntryPath(encodedLocation.substring(separator + 1))?.takeIf(String::isNotEmpty) ?: return null
    return PlaybackLocation(archivePath, entryPath)
  }

  fun displayPath(value: String): String? = parseBrowserPath(value)?.let { location ->
    buildString {
      append(location.archivePath)
      if (location.directory.isNotEmpty()) append("!/").append(location.directory)
    }
  }

  fun breadcrumbs(value: String): List<PathComponent> {
    val location = parseBrowserPath(value) ?: return emptyList()
    val archiveName = File(location.archivePath).name
    return buildList {
      add(PathComponent(archiveName, browserPath(location.archivePath)))
      var directory = ""
      location.directory.split('/').filter(String::isNotEmpty).forEach { segment ->
        directory = if (directory.isEmpty()) segment else "$directory/$segment"
        add(PathComponent(segment, browserPath(location.archivePath, directory)))
      }
    }
  }

  fun playbackUri(archivePath: String, entryPath: String): String {
    val entry = normalizeEntryPath(entryPath) ?: error("Unsafe ZIP entry path")
    require(entry.isNotEmpty()) { "ZIP entry path is empty" }
    val escapedArchivePath = requireNotNull(normalizeArchiveSource(archivePath))
      .replace("%", "%25")
      .replace("|", "%7C")
    return "$PLAYBACK_SCHEME://$escapedArchivePath|$entry"
  }

  fun allMedia(
    context: Context,
    virtualPath: String,
    includeAudio: Boolean,
  ): Result<List<Video>> = runCatching {
    val location = parseBrowserPath(virtualPath) ?: throw IOException("Invalid ZIP folder")
    val archiveFile = File(location.archivePath)
    val archiveName = archiveDisplayName(context, Uri.parse(location.archivePath), archiveFile.takeIf(File::isAbsolute))
    val prefix = location.directory.takeIf(String::isNotEmpty)?.plus('/') ?: ""
    readArchive(context, location.archivePath) { zip ->
      buildList {
        val entries = zip.entries()
        var entryCount = 0
        while (entries.hasMoreElements()) {
          if (++entryCount > MAX_ENTRIES) throw IOException("ZIP archive has too many entries")
          val entry = entries.nextElement()
          val fullPath = normalizedEntryName(entry) ?: continue
          if (entry.isDirectory || !fullPath.startsWith(prefix) || !isPlayable(fullPath, includeAudio)) continue
          add(videoItem(context, location.archivePath, archiveName, archiveFile.lastModified(), entry, fullPath, virtualPath).video)
        }
      }
    }
  }

  fun scan(
    context: Context,
    virtualPath: String,
    includeAudio: Boolean,
  ): Result<List<FileSystemItem>> = runCatching {
    val location = parseBrowserPath(virtualPath) ?: throw IOException("Invalid ZIP folder")
    val archiveFile = File(location.archivePath)
    val archiveName = archiveDisplayName(context, Uri.parse(location.archivePath), archiveFile.takeIf(File::isAbsolute))

    readArchive(context, location.archivePath) { zip ->
      val prefix = location.directory.takeIf(String::isNotEmpty)?.plus('/') ?: ""
      val folders = linkedMapOf<String, FolderStats>()
      val videos = linkedMapOf<String, FileSystemItem.VideoFile>()
      val entries = zip.entries()
      var entryCount = 0

      while (entries.hasMoreElements()) {
        if (++entryCount > MAX_ENTRIES) throw IOException("ZIP archive has too many entries")
        val entry = entries.nextElement()
        val fullPath = normalizedEntryName(entry) ?: continue
        if (!fullPath.startsWith(prefix) || fullPath == location.directory) continue
        val relativePath = fullPath.removePrefix(prefix)
        if (relativePath.isEmpty()) continue

        val separator = relativePath.indexOf('/')
        if (separator >= 0) {
          val childName = relativePath.substring(0, separator)
          if (childName.isEmpty()) continue
          val remainder = relativePath.substring(separator + 1)
          val stats = folders.getOrPut(childName, ::FolderStats)
          if (remainder.trimEnd('/').contains('/')) stats.hasSubfolders = true
          if (!entry.isDirectory && isPlayable(fullPath, includeAudio)) {
            stats.videoCount++
            stats.totalSize += entry.size.coerceAtLeast(0L)
          }
          continue
        }

        if (!entry.isDirectory && isPlayable(fullPath, includeAudio)) {
          videos.putIfAbsent(fullPath, videoItem(context, location.archivePath, archiveName, archiveFile.lastModified(), entry, fullPath, virtualPath))
        }
      }

      buildList {
        folders.forEach { (name, stats) ->
          val childDirectory = if (location.directory.isEmpty()) name else "${location.directory}/$name"
          add(
            FileSystemItem.Folder(
              name = name,
              path = browserPath(location.archivePath, childDirectory),
              lastModified = archiveFile.lastModified(),
              videoCount = stats.videoCount,
              totalSize = stats.totalSize,
              hasSubfolders = stats.hasSubfolders,
            ),
          )
        }
        addAll(videos.values)
      }
    }
  }

  private fun videoItem(
    context: Context,
    archivePath: String,
    archiveName: String,
    archiveModified: Long,
    entry: ZipEntry,
    entryPath: String,
    bucketId: String,
  ): FileSystemItem.VideoFile {
    val displayName = entryPath.substringAfterLast('/')
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val size = entry.size.coerceAtLeast(0L)
    val uri = Uri.parse(playbackUri(archivePath, entryPath))
    val modifiedMillis = entry.time.takeIf { it >= 0L } ?: archiveModified
    val isAudio = extension in FileTypeUtils.AUDIO_EXTENSIONS
    val video = Video(
      id = "$bucketId\u0000$entryPath".hashCode().toLong(),
      title = displayName.substringBeforeLast('.', displayName),
      displayName = displayName,
      path = uri.toString(),
      uri = uri,
      duration = 0L,
      durationFormatted = "",
      size = size,
      sizeFormatted = if (size > 0L) Formatter.formatFileSize(context, size) else "",
      dateModified = modifiedMillis / 1000L,
      dateAdded = archiveModified / 1000L,
      mimeType = FileTypeUtils.getMimeTypeFromExtension(extension),
      bucketId = bucketId,
      bucketDisplayName = archiveName,
      width = 0,
      height = 0,
      fps = 0f,
      resolution = "--",
      isAudio = isAudio,
    )
    return FileSystemItem.VideoFile(displayName, uri.toString(), modifiedMillis, video)
  }

  private fun normalizedEntryName(entry: ZipEntry): String? {
    val normalized = normalizeEntryPath(entry.name) ?: return null
    if (normalized.isEmpty()) return null
    val segments = normalized.split('/')
    if (segments.firstOrNull().equals("__MACOSX", ignoreCase = true) || segments.lastOrNull() == ".DS_Store") return null
    return normalized
  }

  private fun normalizeEntryPath(value: String): String? {
    if ('\u0000' in value || value.startsWith('/') || value.startsWith('\\') || Regex("^[A-Za-z]:").containsMatchIn(value)) return null
    val segments = value.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    return segments.joinToString("/")
  }

  private fun isPlayable(path: String, includeAudio: Boolean): Boolean {
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in FileTypeUtils.VIDEO_EXTENSIONS || includeAudio && extension in FileTypeUtils.AUDIO_EXTENSIONS
  }
}
