/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Watches only the directory currently visible in a browser, never an entire storage tree.
 * Calls back once after bursts of writes/renames. Must stop when the screen leaves RESUMED.
 */
class VisibleDirectoryWatcher(private val onChanged: () -> Unit) {
  private val handler = Handler(Looper.getMainLooper())
  private val dispatch = Runnable { onChanged() }
  private var observer: FileObserver? = null
  private var watchedPath: String? = null

  fun watch(path: String) {
    if (path == watchedPath) return
    stop()
    val directory = File(path)
    if (!directory.isDirectory || !directory.canRead()) return
    val next = createObserver(directory)
    try {
      next.startWatching()
      observer = next
      watchedPath = path
    } catch (error: Exception) {
      Log.w(TAG, "Cannot watch $path", error)
    }
  }

  fun stop() {
    handler.removeCallbacks(dispatch)
    observer?.stopWatching()
    observer = null
    watchedPath = null
  }

  private fun onFileEvent(event: Int) {
    if ((event and EVENTS) == 0) return
    handler.removeCallbacks(dispatch)
    handler.postDelayed(dispatch, 650L)
  }

  private fun createObserver(directory: File): FileObserver =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      object : FileObserver(directory, EVENTS) {
        override fun onEvent(event: Int, path: String?) = onFileEvent(event)
      }
    } else {
      createLegacyObserver(directory)
    }

  // The File constructor was added in API 29; Android 8 and 9 still need the path overload.
  @Suppress("DEPRECATION")
  private fun createLegacyObserver(directory: File): FileObserver =
    object : FileObserver(directory.absolutePath, EVENTS) {
      override fun onEvent(event: Int, path: String?) = onFileEvent(event)
    }

  private companion object {
    const val TAG = "VisibleDirectoryWatcher"
    val EVENTS = FileObserver.CREATE or FileObserver.DELETE or
      FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
      FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
  }
}
