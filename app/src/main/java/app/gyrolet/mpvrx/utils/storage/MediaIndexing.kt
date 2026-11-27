/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks the system media scanner to index files a refresh should show, and waits for it.
 *
 * A file that has just arrived (a finished download, a renamed partial file) may not be in
 * MediaStore yet, or only with incomplete details. Listing right after asking for a scan read the
 * old state, so a new video appeared only after several refreshes (#1032). Paths only go to the
 * scanner; lists still come from MediaStore, which keeps scoped-storage grants in force.
 */
object MediaIndexing {
  private const val TAG = "MediaIndexing"
  private const val SCAN_TIMEOUT_MS = 8_000L
  private const val MAX_FILES = 256
  private const val RECENT_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

  // A file still being written is indexed once its writer closes it; scanning it now records a stub.
  private const val SETTLED_MS = 3_000L

  /**
   * Indexes [paths] off the refresh's critical path; every list reloads when MediaStore changed,
   * or when that cannot be told (no generation token below Android 11).
   */
  suspend fun indexAndNotify(
    context: Context,
    paths: List<String>,
  ) {
    if (paths.isEmpty()) return
    val before = withContext(Dispatchers.IO) { MediaStoreGenerationGuard.currentToken(context) }
    scan(context, paths)
    val changed =
      withContext(Dispatchers.IO) {
        before == null || !MediaStoreGenerationGuard.isTokenCurrent(context, before)
      }
    if (changed) MediaLibraryEvents.notifyChanged()
  }

  /** Scans [paths]; true when every scan finished in time. */
  suspend fun scan(
    context: Context,
    paths: List<String>,
    timeoutMs: Long = SCAN_TIMEOUT_MS,
  ): Boolean {
    if (paths.isEmpty()) return false
    val done = CompletableDeferred<Unit>()
    val remaining = AtomicInteger(paths.size)
    runCatching {
      MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null) { _, _ ->
        if (remaining.decrementAndGet() == 0) done.complete(Unit)
      }
    }.onFailure { error ->
      Log.w(TAG, "Could not request a media scan", error)
      return false
    }
    // A broken vendor scanner must not hold a refresh forever; whatever finished still shows.
    return withTimeoutOrNull(timeoutMs) { done.await() } != null
  }

  /** Playable files directly inside [folderPath]. */
  suspend fun playableFilesIn(folderPath: String): List<String> =
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      File(folderPath)
        .listFiles { file -> file.isPlayable() && now - file.lastModified() >= SETTLED_MS }
        .orEmpty()
        .sortedByDescending(File::lastModified)
        .take(MAX_FILES)
        .map(File::getAbsolutePath)
    }

  /**
   * Recently changed playable files where downloads and recordings land: Download, Movies and
   * DCIM, and one level of app folders inside them (Download/Telegram, DCIM/Camera).
   */
  suspend fun recentPlayableFiles(): List<String> =
    withContext(Dispatchers.IO) {
      val primary = Environment.getExternalStorageDirectory()
      val now = System.currentTimeMillis()
      val roots =
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DCIM)
          .map { File(primary, it) }
      val directories =
        roots +
          roots.flatMap { root ->
            root.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }.orEmpty().toList()
          }
      directories
        .flatMap { directory ->
          directory
            .listFiles { file ->
              val age = now - file.lastModified()
              file.isPlayable() && age in SETTLED_MS..RECENT_WINDOW_MS
            }.orEmpty()
            .toList()
        }.sortedByDescending(File::lastModified)
        .take(MAX_FILES)
        .map(File::getAbsolutePath)
    }

  private fun File.isPlayable(): Boolean =
    isFile && !name.startsWith('.') && (FileTypeUtils.isVideoFile(this) || FileTypeUtils.isAudioFile(this))
}
