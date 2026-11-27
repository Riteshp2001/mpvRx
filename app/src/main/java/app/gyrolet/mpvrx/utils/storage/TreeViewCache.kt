/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.File

/**
 * Blob store for a string snapshot, keyed by an opaque cache key.
 *
 * TreeViewScanner's own cache is process-local, so every cold boot rebuilt the whole folder tree
 * from MediaStore plus a filesystem walk. This lets that tree be persisted and restored instead.
 * [MediaStoreGenerationGuard] separately proves a restored snapshot still matches the device, so
 * this only handles storage: it cannot decide on its own that data is fresh.
 *
 * Lives in `noBackupFilesDir` so the last valid hierarchy survives app restarts and OS cache
 * eviction without being included in Android backups. Snapshots are always generation-validated.
 */
internal object TreeViewCache {
  private const val TAG = "TreeViewCache"
  private const val DIR = "tree_view_cache"

  fun save(
    context: Context,
    cacheKey: String,
    payload: String,
  ) {
    runCatching {
      val file = fileFor(context, cacheKey)
      val temp = File(file.parentFile, file.name + ".tmp")
      // Written to a temp file and renamed, so a kill mid-write cannot leave a truncated snapshot
      // that later decodes into a plausible-looking but wrong tree.
      temp.writeText(payload)
      if (!temp.renameTo(file)) {
        temp.delete()
        throw IllegalStateException("Could not commit tree cache")
      }
      // Badge/scan settings can create many distinct keys over time. Bound on-disk snapshots.
      file.parentFile
        ?.listFiles { child -> child.extension == "tree" }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(8)
        ?.forEach { it.delete() }
    }.onFailure { error ->
      Log.w(TAG, "Could not persist tree cache", error)
    }
  }

  fun load(
    context: Context,
    cacheKey: String,
  ): String? =
    runCatching {
      val file = fileFor(context, cacheKey)
      if (file.isFile) return file.readText().takeIf { it.isNotBlank() }
      // Migrate older snapshots from the OS-evictable cache directory.
      val legacy = context.cacheDir.resolve(DIR).resolve(file.name)
      if (!legacy.isFile) return null
      legacy.readText().takeIf { it.isNotBlank() }?.also { payload ->
        runCatching { file.writeText(payload) }
      }
    }.onFailure { error ->
      Log.w(TAG, "Could not read tree cache", error)
    }.getOrNull()

  fun clear(context: Context) {
    runCatching {
      context.noBackupFilesDir
        .resolve(DIR)
        .takeIf { it.isDirectory }
        ?.listFiles()
        ?.forEach { it.delete() }
      context.cacheDir
        .resolve(DIR)
        .takeIf { it.isDirectory }
        ?.listFiles()
        ?.forEach { it.delete() }
    }.onFailure { error ->
      Log.w(TAG, "Could not clear tree cache", error)
    }
  }

  private fun fileFor(
    context: Context,
    cacheKey: String,
  ): File {
    // Durable across restarts and OS cache eviction, but excluded from device backups.
    val dir = context.noBackupFilesDir.resolve(DIR)
    if (!dir.isDirectory) dir.mkdirs()
    // cacheKey embeds user-facing preference values, so hash it rather than using it as a filename.
    return dir.resolve("${cacheKey.hashCode().toUInt().toString(16)}.tree")
  }
}
