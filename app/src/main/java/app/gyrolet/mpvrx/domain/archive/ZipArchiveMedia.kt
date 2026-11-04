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
import app.gyrolet.mpvrx.utils.media.isPathBasedDocumentAuthority
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.GregorianCalendar
import java.util.Locale
import java.util.zip.ZipException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ZipArchiveMedia {
  private const val BROWSER_SCHEME = "mpvrx-zip"
  private const val BROWSER_AUTHORITY = "local"
  private const val PLAYBACK_SCHEME = "archive"
  private const val MAX_ENTRIES = 100_000
  private const val MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024L * 1024L
  private const val EOCD_SIGNATURE = 0x06054b50
  private const val EOCD_LENGTH = 22
  private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
  private const val ZIP64_LOCATOR_LENGTH = 20
  private const val ZIP64_EOCD_SIGNATURE = 0x06064b50
  private const val ZIP64_EOCD_LENGTH = 56
  private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
  private const val CENTRAL_HEADER_LENGTH = 46
  private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
  private const val LOCAL_HEADER_LENGTH = 30

  private class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val time: Long,
    // Uncompressed and unencrypted, so its bytes can be read in place.
    val stored: Boolean,
    val localHeaderOffset: Long,
  )

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
    // Newest playable entry inside the folder, so Date sorting orders ZIP folders meaningfully.
    var lastModified: Long = 0L,
  )

  private suspend fun resolveZipPath(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
      // Content URIs stay content URIs: their descriptor is read directly, because re-opening the
      // backing file by path is denied without broad storage access.
      val source =
        when (uri.scheme?.lowercase(Locale.ROOT)) {
          "content" -> uri.toString()
          "file" -> uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }?.absolutePath
          else -> null
        } ?: throw IOException("Unsupported ZIP source")
      source
    }

  suspend fun playlistContent(context: Context, uri: Uri): PlaylistContent = withContext(Dispatchers.IO) {
    val source = resolveZipPath(context, uri)
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

  private fun readEntries(context: Context, source: String): List<ArchiveEntry> {
    if (source.startsWith("content://")) {
      return ParcelFileDescriptor.AutoCloseInputStream(openDescriptor(context, Uri.parse(source))).use { stream ->
        readCentralDirectory(stream.channel)
      }
    }
    return RandomAccessFile(source, "r").use { file -> readCentralDirectory(file.channel) }
  }

  private fun readFully(channel: FileChannel, position: Long, length: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    var offset = position
    while (buffer.hasRemaining()) {
      val read = channel.read(buffer, offset)
      if (read < 0) throw EOFException("Truncated ZIP archive")
      offset += read
    }
    buffer.flip()
    return buffer
  }

  private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF

  private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and 0xFFFFFFFFL

  /** Lists entries from the central directory using positional reads only (no path re-open). */
  private fun readCentralDirectory(channel: FileChannel): List<ArchiveEntry> {
    val fileSize = channel.size()
    if (fileSize < EOCD_LENGTH) throw ZipException("Not a ZIP archive")
    val tailLength = minOf(fileSize, EOCD_LENGTH + 0xFFFFL).toInt()
    val tailStart = fileSize - tailLength
    val tail = readFully(channel, tailStart, tailLength)
    val eocd = (tailLength - EOCD_LENGTH downTo 0).firstOrNull { index ->
      tail.getInt(index) == EOCD_SIGNATURE && index + EOCD_LENGTH + tail.u16(index + 20) <= tailLength
    } ?: throw ZipException("Not a ZIP archive")

    var entryCount = tail.u16(eocd + 10).toLong()
    var directorySize = tail.u32(eocd + 12)
    var directoryOffset = tail.u32(eocd + 16)
    var directoryEnd = tailStart + eocd
    if (entryCount == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
      val locatorPosition = directoryEnd - ZIP64_LOCATOR_LENGTH
      if (locatorPosition >= 0L) {
        val locator = readFully(channel, locatorPosition, ZIP64_LOCATOR_LENGTH)
        if (locator.getInt(0) == ZIP64_LOCATOR_SIGNATURE) {
          val zip64Position = locator.getLong(8)
          if (zip64Position !in 0L..(locatorPosition - ZIP64_EOCD_LENGTH)) throw ZipException("Invalid ZIP64 directory")
          val zip64 = readFully(channel, zip64Position, ZIP64_EOCD_LENGTH)
          if (zip64.getInt(0) != ZIP64_EOCD_SIGNATURE) throw ZipException("Invalid ZIP64 directory")
          entryCount = zip64.getLong(32)
          directorySize = zip64.getLong(40)
          directoryOffset = zip64.getLong(48)
          directoryEnd = zip64Position
        }
      }
    }
    if (entryCount !in 0L..MAX_ENTRIES.toLong()) throw IOException("ZIP archive has too many entries")
    if (directorySize !in 0L..minOf(directoryEnd, MAX_CENTRAL_DIRECTORY_BYTES)) {
      throw ZipException("Invalid ZIP central directory")
    }

    // The directory immediately precedes its end record; this also tolerates prefixed archives.
    val directory = readFully(channel, directoryEnd - directorySize, directorySize.toInt())
    // A prefixed archive records offsets from the start of the original ZIP.
    val offsetShift = directoryEnd - directorySize - directoryOffset
    val bytes = directory.array()
    val entries = ArrayList<ArchiveEntry>(entryCount.toInt())
    var position = 0
    while (position + CENTRAL_HEADER_LENGTH <= directory.limit() &&
      directory.getInt(position) == CENTRAL_HEADER_SIGNATURE
    ) {
      if (entries.size >= MAX_ENTRIES) throw IOException("ZIP archive has too many entries")
      val nameLength = directory.u16(position + 28)
      val extraLength = directory.u16(position + 30)
      val commentLength = directory.u16(position + 32)
      val nameStart = position + CENTRAL_HEADER_LENGTH
      val next = nameStart + nameLength + extraLength + commentLength
      if (next > directory.limit()) throw ZipException("Truncated ZIP central directory")

      val name = String(bytes, nameStart, nameLength, Charsets.UTF_8)
      var size = directory.u32(position + 24)
      var compressedSize = directory.u32(position + 20)
      var localHeaderOffset = directory.u32(position + 42)
      if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL || localHeaderOffset == 0xFFFFFFFFL) {
        var extra = nameStart + nameLength
        val extraEnd = extra + extraLength
        while (extra + 4 <= extraEnd) {
          val id = directory.u16(extra)
          val length = directory.u16(extra + 2)
          if (id == 0x0001) {
            // ZIP64 lists only the overflowed fields, in this order.
            var field = extra + 4
            val fieldEnd = minOf(field + length, extraEnd)
            if (size == 0xFFFFFFFFL && field + 8 <= fieldEnd) {
              size = directory.getLong(field)
              field += 8
            }
            if (compressedSize == 0xFFFFFFFFL && field + 8 <= fieldEnd) {
              compressedSize = directory.getLong(field)
              field += 8
            }
            if (localHeaderOffset == 0xFFFFFFFFL && field + 8 <= fieldEnd) localHeaderOffset = directory.getLong(field)
            break
          }
          extra += 4 + length
        }
      }
      val encrypted = (directory.u16(position + 8) and 1) != 0
      entries += ArchiveEntry(
        name = name,
        isDirectory = name.endsWith('/') || name.endsWith('\\'),
        size = size,
        time = dosToJavaTime(directory.u16(position + 14), directory.u16(position + 12)),
        stored = directory.u16(position + 10) == 0 && !encrypted && compressedSize == size,
        localHeaderOffset = localHeaderOffset + offsetShift,
      )
      position = next
    }
    if (entries.size < entryCount) throw ZipException("Invalid ZIP central directory")
    return entries
  }

  private fun dosToJavaTime(date: Int, time: Int): Long {
    if (date == 0) return -1L
    return GregorianCalendar(
      ((date shr 9) and 0x7F) + 1980,
      ((date shr 5) and 0x0F) - 1,
      date and 0x1F,
      (time shr 11) and 0x1F,
      (time shr 5) and 0x3F,
      (time and 0x1F) * 2,
    ).timeInMillis
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
      // mpv reads the held descriptor through fd:// (not closed by mpv); the session closes it.
      OpenedPlayback(nativePlaybackUri("fd://${descriptor.fd}", location.entryPath), descriptor)
    } catch (error: Exception) {
      descriptor.close()
      throw error
    }
  }

  /** A stored entry's byte range inside its archive; close it once the reader is done. */
  class StoredEntry(
    private val descriptor: ParcelFileDescriptor,
    val offset: Long,
    val length: Long,
  ) : Closeable {
    val fileDescriptor: FileDescriptor get() = descriptor.fileDescriptor

    override fun close() = descriptor.close()
  }

  /**
   * Opens the entry behind playback URI [value] for reading in place. Returns null unless the entry
   * is stored uncompressed: compressed entries are never inflated or extracted.
   */
  fun openStoredEntry(context: Context, value: String): StoredEntry? {
    val location = parsePlaybackLocation(value) ?: return null
    val descriptor =
      if (location.archivePath.startsWith("content://")) {
        openDescriptor(context, Uri.parse(location.archivePath))
      } else {
        ParcelFileDescriptor.open(File(location.archivePath), ParcelFileDescriptor.MODE_READ_ONLY)
      }
    val range =
      try {
        // Only the duplicate closes with this stream; the decoder reads through the original.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use { storedRange(it.channel, location.entryPath) }
      } catch (error: Exception) {
        descriptor.close()
        throw error
      }
    if (range == null) {
      descriptor.close()
      return null
    }
    return StoredEntry(descriptor, range.first, range.second)
  }

  /** Start and length of [entryPath]'s bytes, or null unless the entry is stored uncompressed. */
  private fun storedRange(channel: FileChannel, entryPath: String): Pair<Long, Long>? {
    val entry =
      readCentralDirectory(channel).firstOrNull { !it.isDirectory && normalizedEntryName(it) == entryPath }
        ?: return null
    if (!entry.stored || entry.size <= 0L || entry.localHeaderOffset < 0L) return null
    val header = readFully(channel, entry.localHeaderOffset, LOCAL_HEADER_LENGTH)
    if (header.getInt(0) != LOCAL_HEADER_SIGNATURE) return null
    val start = entry.localHeaderOffset + LOCAL_HEADER_LENGTH + header.u16(26) + header.u16(28)
    return (start to entry.size).takeIf { start + entry.size <= channel.size() }
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
    if (isPathBasedDocumentAuthority(uri.authority)) {
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
    val volumeFile = when {
      isPathBasedDocumentAuthority(uri.authority) -> {
        val volumeId = runCatching { DocumentsContract.getDocumentId(uri).substringBefore(':') }.getOrNull() ?: return false
        if (volumeId == "primary") Environment.getExternalStorageDirectory()
        else if (volumeId.matches(Regex("[A-Za-z0-9-]+"))) File("/storage/$volumeId")
        else return false
      }
      uri.authority == "com.android.providers.downloads.documents" ||
        uri.authority == "com.android.providers.media.documents" -> Environment.getExternalStorageDirectory()
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

  /**
   * Modification time of the archive itself. `File(content://...)` always reports 0, so picked
   * archives ask their document provider instead.
   */
  private fun archiveModifiedMillis(
    context: Context,
    archivePath: String,
    archiveFile: File,
  ): Long {
    if (archiveFile.isAbsolute) return archiveFile.lastModified().coerceAtLeast(0L)
    val uri = Uri.parse(archivePath)
    if (uri.scheme != "content") return 0L
    return runCatching {
      context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
      }
    }.getOrNull()?.coerceAtLeast(0L) ?: 0L
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

  fun breadcrumbs(context: Context, value: String): List<PathComponent> {
    val location = parseBrowserPath(value) ?: return emptyList()
    val archiveFile = File(location.archivePath)
    val archiveName = archiveDisplayName(context, Uri.parse(location.archivePath), archiveFile.takeIf(File::isAbsolute))
    return buildList {
      add(PathComponent(archiveName, browserPath(location.archivePath)))
      var directory = ""
      location.directory.split('/').filter(String::isNotEmpty).forEach { segment ->
        directory = if (directory.isEmpty()) segment else "$directory/$segment"
        add(PathComponent(segment, browserPath(location.archivePath, directory)))
      }
    }
  }

  fun playbackUri(archivePath: String, entryPath: String): String =
    nativePlaybackUri(requireNotNull(normalizeArchiveSource(archivePath)), entryPath)

  private fun nativePlaybackUri(source: String, entryPath: String): String {
    val entry = normalizeEntryPath(entryPath) ?: error("Unsafe ZIP entry path")
    require(entry.isNotEmpty()) { "ZIP entry path is empty" }
    val escapedArchivePath = source
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
    val archiveModified = archiveModifiedMillis(context, location.archivePath, archiveFile)
    val prefix = location.directory.takeIf(String::isNotEmpty)?.plus('/') ?: ""
    readEntries(context, location.archivePath).mapNotNull { entry ->
      val fullPath = normalizedEntryName(entry) ?: return@mapNotNull null
      if (entry.isDirectory || !fullPath.startsWith(prefix) || !isPlayable(fullPath, includeAudio)) return@mapNotNull null
      videoItem(context, location.archivePath, archiveName, archiveModified, entry, fullPath, virtualPath).video
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
    val archiveModified = archiveModifiedMillis(context, location.archivePath, archiveFile)

    val prefix = location.directory.takeIf(String::isNotEmpty)?.plus('/') ?: ""
    val folders = linkedMapOf<String, FolderStats>()
    val videos = linkedMapOf<String, FileSystemItem.VideoFile>()

    for (entry in readEntries(context, location.archivePath)) {
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
          stats.lastModified = maxOf(stats.lastModified, entry.time.takeIf { it > 0L } ?: archiveModified)
        }
        continue
      }

      if (!entry.isDirectory && isPlayable(fullPath, includeAudio)) {
        videos.putIfAbsent(fullPath, videoItem(context, location.archivePath, archiveName, archiveModified, entry, fullPath, virtualPath))
      }
    }

    buildList {
      folders.forEach { (name, stats) ->
        // Folders without playable media would only lead to empty pages.
        if (stats.videoCount == 0) return@forEach
        val childDirectory = if (location.directory.isEmpty()) name else "${location.directory}/$name"
        add(
          FileSystemItem.Folder(
            name = name,
            path = browserPath(location.archivePath, childDirectory),
            lastModified = stats.lastModified.takeIf { it > 0L } ?: archiveModified,
            videoCount = stats.videoCount,
            totalSize = stats.totalSize,
            hasSubfolders = stats.hasSubfolders,
          ),
        )
      }
      addAll(videos.values)
    }
  }

  private fun videoItem(
    context: Context,
    archivePath: String,
    archiveName: String,
    archiveModified: Long,
    entry: ArchiveEntry,
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

  private fun normalizedEntryName(entry: ArchiveEntry): String? {
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
