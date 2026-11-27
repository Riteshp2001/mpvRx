/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.download

import app.gyrolet.mpvrx.database.dao.DownloadItemDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

class DownloadOutputReservations(
  private val dao: DownloadItemDao,
) {
  data class Reservation(
    val directory: String,
    val fileName: String,
  ) {
    val baseName: String get() = fileName.substringBeforeLast('.', fileName)
  }

  private val mutex = Mutex()
  private val held = mutableSetOf<Reservation>()

  suspend fun reserve(
    directory: File,
    requestedFileName: String,
  ): Reservation =
    mutex.withLock {
      if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory || !directory.canWrite()) {
        throw IOException("Download directory is not writable")
      }
      val path = directory.canonicalPath
      val files = directory.listFiles() ?: throw IOException("Could not inspect download directory")
      val registered = dao.getAll().filter { File(it.dirPath).canonicalPath == path }
      val reservedBases =
        registered.map { it.fileName.substringBeforeLast('.', it.fileName).lowercase(Locale.ROOT) }.toSet() +
          held.filter { it.directory == path }.map { it.baseName.lowercase(Locale.ROOT) }
      val name = File(requestedFileName).name
      val base = DownloadLocations.sanitizeName(name.substringBeforeLast('.', name))
      val extension =
        name
          .substringAfterLast('.', "")
          .takeIf { '.' in name && it.isNotBlank() }
          ?.let { DownloadLocations.sanitizeName(it) }
          .orEmpty()
      var counter = 0
      var candidate = base

      fun occupied(): Boolean =
        candidate.lowercase(Locale.ROOT) in reservedBases ||
          files.any {
            it.name.equals(
              candidate,
              ignoreCase = true,
            ) ||
              it.name.startsWith("$candidate.", ignoreCase = true)
          }
      while (occupied()) {
        counter++
        candidate = "$base ($counter)"
      }
      Reservation(path, candidate + if (extension.isEmpty()) "" else ".$extension").also { held.add(it) }
    }

  suspend fun release(reservation: Reservation) {
    mutex.withLock { held.remove(reservation) }
  }

  companion object {
    internal fun publish(
      source: File,
      destination: File,
    ): File {
      check(source.isFile && source.length() > 0L) { "Download produced no output" }
      Files.move(source.toPath(), destination.toPath())
      return destination
    }
  }
}
