/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.framecapture

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A slice of the heap for decoded snapshots, however much the process was actually given.
 *
 * The floor matters more than the ratio: a phone screen shows a couple of dozen tiles at once, and a
 * cache that cannot hold several screens' worth evicts bitmaps that are still on screen, so every
 * scroll re-decodes what the user is already looking at.
 */
private const val MIN_THUMBNAIL_CACHE_BYTES = 24L * 1024 * 1024
private const val MAX_THUMBNAIL_CACHE_BYTES = 64L * 1024 * 1024

/**
 * Decoded snapshot thumbnails keyed by capture id, shared by every screen that shows snapshot cells.
 *
 * Extracted rather than duplicated because the library page and a folder page both need it, and the
 * interesting part is not the map but the in-flight de-duplication: a grid can compose the same item
 * several times per frame, and decoding a multi-megapixel frame per recomposition would sink the list.
 *
 * The cache is bounded by bytes, not by count. A folder is unbounded — hundreds of frames is
 * ordinary — and holding a decoded bitmap for every one of them for the life of the screen is how a
 * large library runs a gallery out of heap. Evicted bitmaps are decoded again if scrolled back to.
 *
 * [scope] is the owner's scope, so the work dies with the screen that asked for it.
 */
class SnapshotThumbnailStore(
  private val context: Context,
  private val scope: CoroutineScope,
  private val targetMaxPx: Int = SnapshotImageLoader.THUMBNAIL_MAX_PX,
) {

  private val cache =
    object : LruCache<Long, Bitmap>(cacheBudgetBytes()) {
      override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount

      override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
        publish()
      }
    }

  private val _thumbnails = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
  val thumbnails: StateFlow<Map<Long, Bitmap>> = _thumbnails.asStateFlow()

  private val inFlight = mutableSetOf<Long>()

  fun load(capture: FrameCapture) {
    if (cache.get(capture.id) != null || !inFlight.add(capture.id)) return
    scope.launch {
      try {
        val bitmap =
          withContext(Dispatchers.IO) {
            SnapshotImageLoader.load(
              context = context,
              imageUri = capture.imageUri,
              imagePath = capture.imagePath,
              targetMaxPx = targetMaxPx,
            )
          }
        if (bitmap != null) {
          cache.put(capture.id, bitmap)
          publish()
        }
      } finally {
        inFlight.remove(capture.id)
      }
    }
  }

  /** Drops bitmaps for records that no longer exist, so a recycled id cannot show a stale image. */
  fun forget(ids: Collection<Long>) {
    ids.forEach(cache::remove)
  }

  private fun publish() {
    _thumbnails.value = cache.snapshot()
  }

  private fun cacheBudgetBytes(): Int {
    // Two stores can be alive at once — the library page and a folder page — so each takes an eighth.
    val perStore = Runtime.getRuntime().maxMemory() / 8
    return perStore.coerceIn(MIN_THUMBNAIL_CACHE_BYTES, MAX_THUMBNAIL_CACHE_BYTES).toInt()
  }
}
