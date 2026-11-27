// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.utils.memory

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.util.LruCache
import app.gyrolet.mpvrx.utils.device.DevicePerformance
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/** A bounded bitmap cache, sized in KiB, that participates in application memory trimming. */
internal open class BitmapMemoryCache<K : Any>(
  heapDivisor: Int,
  maxSizeMiB: Int,
) : LruCache<K, Bitmap>(budgetKiB(heapDivisor, maxSizeMiB)) {
  init {
    BitmapMemoryCaches.register(this)
  }

  override fun sizeOf(
    key: K,
    value: Bitmap,
  ): Int = ((value.allocationByteCount.toLong() + 1023L) / 1024L).coerceAtLeast(1L).toInt()

  internal fun trimMemory(evict: Boolean) {
    if (evict) evictAll() else trimToSize(size() / 2)
    onMemoryTrim()
  }

  /** Publish UI snapshots once after a bulk trim, rather than allocating a map per eviction. */
  protected open fun onMemoryTrim() = Unit
}

private fun budgetKiB(
  heapDivisor: Int,
  maxSizeMiB: Int,
): Int {
  val capKiB = maxSizeMiB * 1024 / if (DevicePerformance.isLowEnd) 2 else 1
  // largeHeap is also used by playback: it must not turn each UI cache into hundreds of MiB.
  return (Runtime.getRuntime().maxMemory() / 1024L / heapDivisor)
    .coerceIn(1L, capKiB.toLong())
    .toInt()
}

internal object BitmapMemoryCaches {
  private val caches = CopyOnWriteArrayList<WeakReference<BitmapMemoryCache<*>>>()

  fun register(cache: BitmapMemoryCache<*>) {
    caches.add(WeakReference(cache))
  }

  @Suppress("DEPRECATION")
  fun trimMemory(level: Int) {
    val evict =
      level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    val halve =
      level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    if (!evict && !halve) return
    for (reference in caches) {
      val cache = reference.get()
      if (cache == null) {
        caches.remove(reference)
      } else {
        cache.trimMemory(evict)
      }
    }
    // Bitmaps still displayed by Compose, PiP or a widget remain valid. Never recycle them here,
    // clear generated disk thumbnails, or tear down a playing media session to free UI caches.
  }
}
