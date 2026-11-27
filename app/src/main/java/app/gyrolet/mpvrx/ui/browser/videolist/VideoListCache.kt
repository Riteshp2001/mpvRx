/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.videolist

import android.content.Context
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.ui.browser.medialibrary.MediaLibraryCache
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.File

/**
 * Last-known contents of each opened folder, so reopening the app shows a folder's videos
 * immediately instead of re-querying and re-probing every file first.
 *
 * Stale-while-revalidate: callers always revalidate in the background after a restore and replace
 * the list only when the fresh scan differs. Kept in `noBackupFilesDir` so OS cache eviction does
 * not throw it away, while staying out of device backups.
 */
internal object VideoListCache {
  private const val TAG = "VideoListCache"
  private const val DIR = "video_list_cache"
  private const val MAX_FOLDERS = 64

  // Reject snapshots whose launch rotation was read from the old MediaStore column.
  private const val HEADER_PREFIX = "orientation_v2|"

  fun load(
    context: Context,
    key: String,
  ): List<Video>? =
    runCatching {
      val file = fileFor(context, key)
      if (!file.isFile) return null
      val lines = file.readText().split('\n', limit = 2)
      // The key is stored inside the file so a hash collision cannot show another folder's videos.
      if (lines.size != 2 || lines[0] != HEADER_PREFIX + key || lines[1].isEmpty()) return null
      val records = lines[1].split(MediaLibraryCache.RECORD_SEPARATOR)
      records.mapNotNull { MediaLibraryCache.decode(it) }.takeIf { it.size == records.size }
    }.onFailure { error ->
      Log.w(TAG, "Could not read video list cache", error)
    }.getOrNull()

  fun save(
    context: Context,
    key: String,
    videos: List<Video>,
  ) {
    runCatching {
      val file = fileFor(context, key)
      if (videos.isEmpty()) {
        file.delete()
        return
      }
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(
        HEADER_PREFIX + key.replace('\n', ' ') + "\n" +
          videos.joinToString(MediaLibraryCache.RECORD_SEPARATOR) { MediaLibraryCache.encode(it) },
      )
      if (!temp.renameTo(file)) {
        temp.delete()
        return
      }
      file.parentFile
        ?.listFiles { child -> child.extension == "videos" }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(MAX_FOLDERS)
        ?.forEach { it.delete() }
    }.onFailure { error ->
      Log.w(TAG, "Could not persist video list cache", error)
    }
  }

  private fun fileFor(
    context: Context,
    key: String,
  ): File {
    val dir = context.noBackupFilesDir.resolve(DIR)
    if (!dir.isDirectory) dir.mkdirs()
    return dir.resolve("${key.hashCode().toUInt().toString(16)}.videos")
  }
}
