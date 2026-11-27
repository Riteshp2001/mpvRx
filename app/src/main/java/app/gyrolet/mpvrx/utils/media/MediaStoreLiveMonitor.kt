/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Foreground-only MediaStore observer. Reacts to indexed media changes without periodically
 * walking the entire filesystem. On foreground return compares generations to recover missed
 * changes while the observer was unregistered.
 */
class MediaStoreLiveMonitor(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var publishPending = false
  private val publish =
    Runnable {
      publishPending = false
      MediaLibraryEvents.notifyChanged()
    }
  private var registered = false
  private var lastGeneration: String? = null
  private var hasStarted = false
  private var sessionId = 0
  private val observer =
    object : ContentObserver(handler) {
      override fun onChange(selfChange: Boolean) = schedule()

      override fun onChange(
        selfChange: Boolean,
        uri: android.net.Uri?,
      ) = schedule()
    }

  private fun schedule() {
    // Bound the delay even while a large extraction keeps writing media rows.
    if (!registered || publishPending) return
    publishPending = true
    handler.postDelayed(publish, 650L)
  }

  fun start() {
    if (registered) return
    registered = true
    val session = ++sessionId
    try {
      val resolver = context.contentResolver
      resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
      resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    } catch (error: Exception) {
      Log.w(TAG, "Could not register media observers", error)
      stop()
      return
    }
    val previous = lastGeneration
    val returning = hasStarted
    hasStarted = true
    scope.launch(Dispatchers.IO) {
      // Compare against the playable library only: a raw generation also moves for every photo,
      // screenshot or download, which used to force a full rescan on each foreground return.
      // Pre-Android 11 and failing vendor providers have no generation token. Unknown must
      // trigger a foreground re-query, rather than being treated as unchanged forever.
      val changed = returning && (previous == null || !MediaStoreGenerationGuard.isTokenCurrent(context, previous))
      val current = if (previous == null || changed) MediaStoreGenerationGuard.currentToken(context) else previous
      handler.post {
        if (!registered || session != sessionId) return@post
        if (changed) schedule()
        if (current != null) lastGeneration = current
      }
    }
  }

  fun stop() {
    if (!registered) return
    registered = false
    sessionId++
    runCatching { context.contentResolver.unregisterContentObserver(observer) }
    handler.removeCallbacks(publish)
    publishPending = false
  }

  private companion object {
    const val TAG = "MediaStoreLiveMonitor"
  }
}
