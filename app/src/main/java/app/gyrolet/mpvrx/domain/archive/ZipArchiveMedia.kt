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
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.browser.PathComponent
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

  private data class FolderStats(
    var videoCount: Int = 0,
    var totalSize: Long = 0L,
    var hasSubfolders: Boolean = false,
  )

  private data class ArchiveStats(
    var videoCount: Int = 0,
    var totalSize: Long = 0L,
    var hasSubfolders: Boolean = false,
  )

  private data class StatsKey(
    val path: String,
    val modified: Long,
    val length: Long,
    val includeAudio: Boolean,
  )

  private val statsCache = ConcurrentHashMap<StatsKey, ArchiveStats>()

  fun isZipFile(file: File): Boolean = file.isFile && file.extension.equals("zip", ignoreCase = true)

  /**
   * Resolves a picked ZIP to its real, readable location on storage.
   *
   * Archives are kept read-only in place: nothing is copied into app storage and no entry is
   * extracted to cache, so a 20 GB archive costs nothing beyond the file the user already has.
   * Returns null when the URI cannot be mapped to an accessible filesystem path (for example a
   * provider that only exposes a stream).
   */
  suspend fun resolveZipPath(
    context: Context,
    uri: Uri,
  ): String? =
    withContext(Dispatchers.IO) {
      try {
        val localFile =
          when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let(::File)
            "content" -> runCatching { uri.resolveLocalPath(context) }.getOrNull()?.let(::File)
            else -> null
          }

        localFile
          ?.takeIf { file -> isZipFile(file) && isReadableZipArchive(file) }
          ?.absolutePath
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }
    }

  /** Reads (and caches) the playable contents of an archive without extracting any entry. */
  private fun inspectArchive(
    archive: File,
    includeAudio: Boolean,
  ): ArchiveStats =
    statsCache.getOrPut(StatsKey(archive.absolutePath, archive.lastModified(), archive.length(), includeAudio)) {
      ZipFile(archive).use { zip ->
        val stats = ArchiveStats()
        val entries = zip.entries()
        var entryCount = 0
        while (entries.hasMoreElements()) {
          if (++entryCount > MAX_ENTRIES) throw IOException("ZIP archive has too many entries")
          val entry = entries.nextElement()
          val path = normalizedEntryName(entry) ?: continue
          if (path.contains('/')) stats.hasSubfolders = true
          if (!entry.isDirectory && isPlayable(path, includeAudio)) {
            stats.videoCount++
            stats.totalSize += entry.size.coerceAtLeast(0L)
          }
        }
        stats
      }
    }

  /**
   * Builds a browsable folder for an archive the user added, reporting scan progress as the
   * entries are read. Returns null when the archive is missing or unreadable.
   */
  suspend fun registerArchive(
    archivePath: String,
    includeAudio: Boolean,
    onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
  ): VideoFolder? =
    withContext(Dispatchers.IO) {
      val archive = File(archivePath)
      if (!isZipFile(archive) || !archive.canRead()) return@withContext null

      try {
        ZipFile(archive).use { zip ->
          val total = zip.size().coerceAtLeast(0)
          val stats = ArchiveStats()
          val entries = zip.entries()
          var scanned = 0
          while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            scanned++
            if (scanned == total || scanned % 128 == 0) onProgress(scanned, total)
            val entry = entries.nextElement()
            val path = normalizedEntryName(entry) ?: continue
            if (path.contains('/')) stats.hasSubfolders = true
            if (!entry.isDirectory && isPlayable(path, includeAudio)) {
              stats.videoCount++
              stats.totalSize += entry.size.coerceAtLeast(0L)
            }
          }
          val bucketId = browserPath(archive.absolutePath)
          VideoFolder(
            bucketId = bucketId,
            name = archive.name,
            path = bucketId,
            videoCount = stats.videoCount,
            totalSize = stats.totalSize.takeIf { it > 0L } ?: archive.length(),
            totalDuration = 0L,
            lastModified = archive.lastModified() / 1000L,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }
    }

  /** Folders for archives the user has added, so they stay available after a restart. */
  fun persistedFolders(
    archivePaths: Collection<String>,
    includeAudio: Boolean,
  ): List<VideoFolder> =
    archivePaths.mapNotNull { path ->
      val archive = File(path)
      if (!isZipFile(archive) || !archive.canRead()) return@mapNotNull null
      val stats = runCatching { inspectArchive(archive, includeAudio) }.getOrNull() ?: return@mapNotNull null
      val bucketId = browserPath(archive.absolutePath)
      VideoFolder(
        bucketId = bucketId,
        name = archive.name,
        path = bucketId,
        videoCount = stats.videoCount,
        totalSize = stats.totalSize.takeIf { it > 0L } ?: archive.length(),
        totalDuration = 0L,
        lastModified = archive.lastModified() / 1000L,
      )
    }

  private fun isReadableZipArchive(file: File): Boolean =
    file.isFile &&
      file.canRead() &&
      runCatching {
        ZipFile(file).use { archive -> archive.size() <= MAX_ENTRIES }
      }.getOrDefault(false)

  fun browserPath(
    archivePath: String,
    directory: String = "",
  ): String {
    val normalizedDirectory = normalizeEntryPath(directory).orEmpty()
    return Uri.Builder()
      .scheme(BROWSER_SCHEME)
      .authority(BROWSER_AUTHORITY)
      .appendQueryParameter("archive", File(archivePath).absolutePath)
      .appendQueryParameter("directory", normalizedDirectory)
      .build()
      .toString()
  }

  fun parseBrowserPath(value: String): Location? = runCatching {
    val uri = Uri.parse(value)
    if (!uri.scheme.equals(BROWSER_SCHEME, ignoreCase = true) || uri.authority != BROWSER_AUTHORITY) {
      return@runCatching null
    }
    val archivePath = uri.getQueryParameter("archive")?.takeIf { File(it).isAbsolute } ?: return@runCatching null
    val directory = normalizeEntryPath(uri.getQueryParameter("directory").orEmpty()) ?: return@runCatching null
    Location(File(archivePath).absolutePath, directory)
  }.getOrNull()

  fun isBrowserPath(value: String): Boolean = parseBrowserPath(value) != null

  fun isArchiveRoot(value: String): Boolean = parseBrowserPath(value)?.directory?.isEmpty() == true

  fun isPlaybackUri(value: String): Boolean = parsePlaybackLocation(value) != null

  private fun parsePlaybackLocation(value: String): Location? {
    val prefix = "$PLAYBACK_SCHEME://"
    if (!value.startsWith(prefix, ignoreCase = true)) return null
    val encodedLocation = value.substring(prefix.length)
    val separator = encodedLocation.indexOf('|')
    if (separator <= 0 || separator == encodedLocation.lastIndex) return null

    val archivePath = runCatching { Uri.decode(encodedLocation.substring(0, separator)) }.getOrNull() ?: return null
    if (!File(archivePath).isAbsolute) return null
    val entryPath = normalizeEntryPath(encodedLocation.substring(separator + 1))?.takeIf(String::isNotEmpty) ?: return null
    return Location(File(archivePath).absolutePath, entryPath)
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

  fun playbackUri(
    archivePath: String,
    entryPath: String,
  ): String {
    val entry = normalizeEntryPath(entryPath) ?: error("Unsafe ZIP entry path")
    require(entry.isNotEmpty()) { "ZIP entry path is empty" }
    val escapedArchivePath = File(archivePath).absolutePath
      .replace("%", "%25")
      .replace("|", "%7C")
    return "$PLAYBACK_SCHEME://$escapedArchivePath|$entry"
  }

  fun archiveFoldersIn(
    directory: File,
    includeAudio: Boolean,
  ): List<FileSystemItem.Folder> =
    directory.listFiles()
      ?.asSequence()
      ?.filter(::isZipFile)
      ?.map { archive -> archiveFolder(archive, includeAudio) }
      ?.toList()
      .orEmpty()

  @Suppress("DEPRECATION")
  fun mediaStoreFolders(
    context: Context,
    includeAudio: Boolean,
  ): List<VideoFolder> = runCatching {
    val result = linkedMapOf<String, VideoFolder>()
    context.contentResolver.query(
      MediaStore.Files.getContentUri("external"),
      arrayOf(MediaStore.MediaColumns.DATA),
      "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
      arrayOf("%.zip"),
      null,
    )?.use { cursor ->
      val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
      while (cursor.moveToNext()) {
        val archive = dataIndex.takeIf { it >= 0 }?.let(cursor::getString)?.let(::File) ?: continue
        if (!isZipFile(archive) || !archive.canRead()) continue
        val folder = archiveFolder(archive, includeAudio)
        if (folder.videoCount <= 0) continue
        result[archive.absolutePath.lowercase(Locale.ROOT)] = VideoFolder(
          bucketId = folder.path,
          name = folder.name,
          path = folder.path,
          videoCount = folder.videoCount,
          totalSize = folder.totalSize,
          totalDuration = 0L,
          lastModified = folder.lastModified / 1000L,
        )
      }
    }
    result.values.toList()
  }.getOrDefault(emptyList())

  fun allMedia(
    context: Context,
    virtualPath: String,
    includeAudio: Boolean,
  ): Result<List<Video>> = runCatching {
    val location = parseBrowserPath(virtualPath) ?: throw IOException("Invalid ZIP folder")
    val archiveFile = File(location.archivePath)
    if (!isZipFile(archiveFile) || !archiveFile.canRead()) throw IOException("Cannot read ZIP archive")
    val prefix = location.directory.takeIf(String::isNotEmpty)?.plus('/') ?: ""
    ZipFile(archiveFile).use { zip ->
      buildList {
        val entries = zip.entries()
        var entryCount = 0
        while (entries.hasMoreElements()) {
          if (++entryCount > MAX_ENTRIES) throw IOException("ZIP archive has too many entries")
          val entry = entries.nextElement()
          val fullPath = normalizedEntryName(entry) ?: continue
          if (entry.isDirectory || !fullPath.startsWith(prefix) || !isPlayable(fullPath, includeAudio)) continue
          add(videoItem(context, archiveFile, entry, fullPath, virtualPath).video)
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
    if (!isZipFile(archiveFile) || !archiveFile.canRead()) throw IOException("Cannot read ZIP archive")

    ZipFile(archiveFile).use { zip ->
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
          videos.putIfAbsent(fullPath, videoItem(context, archiveFile, entry, fullPath, virtualPath))
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

  private fun archiveFolder(
    archive: File,
    includeAudio: Boolean,
  ): FileSystemItem.Folder {
    val stats = runCatching { inspectArchive(archive, includeAudio) }.getOrNull()
    return FileSystemItem.Folder(
      name = archive.name,
      path = browserPath(archive.absolutePath),
      lastModified = archive.lastModified(),
      videoCount = stats?.videoCount ?: 0,
      totalSize = stats?.totalSize ?: archive.length(),
      hasSubfolders = stats?.hasSubfolders == true,
    )
  }

  private fun videoItem(
    context: Context,
    archive: File,
    entry: ZipEntry,
    entryPath: String,
    bucketId: String,
  ): FileSystemItem.VideoFile {
    val displayName = entryPath.substringAfterLast('/')
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val size = entry.size.coerceAtLeast(0L)
    val uri = Uri.parse(playbackUri(archive.absolutePath, entryPath))
    val modifiedMillis = entry.time.takeIf { it >= 0L } ?: archive.lastModified()
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
      dateAdded = archive.lastModified() / 1000L,
      mimeType = FileTypeUtils.getMimeTypeFromExtension(extension),
      bucketId = bucketId,
      bucketDisplayName = File(archive.absolutePath).name,
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
    val segments = value.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) return null
    return segments.joinToString("/")
  }

  private fun isPlayable(
    path: String,
    includeAudio: Boolean,
  ): Boolean {
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in FileTypeUtils.VIDEO_EXTENSIONS || includeAudio && extension in FileTypeUtils.AUDIO_EXTENSIONS
  }
}
