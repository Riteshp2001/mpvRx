// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.utils.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Coarse device tier for defaults whose cost is paid on every frame: GPU backdrop effects behind
 * scrolling lists, and rendering video through a TextureView so it can be sampled for glass.
 * Only defaults depend on it; an explicit user choice always wins.
 */
object DevicePerformance {
  // Reported totals sit below the nominal size: 3 GB devices report about 2.8 GiB, 4 GB about 3.6.
  private const val LOW_END_TOTAL_MEMORY_BYTES = 3_200L * 1024L * 1024L

  @Volatile private var appContext: Context? = null

  @Volatile private var lowEnd: Boolean? = null

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  /** False until [init] has run, so the defaults never change mid-process for lack of a context. */
  val isLowEnd: Boolean
    get() {
      lowEnd?.let { return it }
      val context = appContext ?: return false
      return synchronized(this) {
        lowEnd ?: detect(context).also { lowEnd = it }
      }
    }

  private fun detect(context: Context): Boolean =
    runCatching {
      val activityManager = context.getSystemService(ActivityManager::class.java)
      when {
        activityManager?.isLowRamDevice == true -> true
        // Android 10 and older: budget hardware in practice, and no RenderEffect for glass anyway.
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> true
        (activityManager?.memoryClass ?: 0) in 1..192 -> true
        else -> {
          val totalMemory =
            readTotalMemoryBytes()
              ?: activityManager?.let { manager ->
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
              }
              ?: 0L
          totalMemory in 1 until LOW_END_TOTAL_MEMORY_BYTES
        }
      }
    }.getOrDefault(false)

  /** Local file read instead of ActivityManager's binder call; this runs during startup. */
  private fun readTotalMemoryBytes(): Long? =
    runCatching {
      File("/proc/meminfo").bufferedReader().use { reader ->
        reader
          .lineSequence()
          .firstOrNull { it.startsWith("MemTotal:") }
          ?.substringAfter(':')
          ?.trim()
          ?.substringBefore(' ')
          ?.toLongOrNull()
          ?.times(1024L)
      }
    }.getOrNull()
}
