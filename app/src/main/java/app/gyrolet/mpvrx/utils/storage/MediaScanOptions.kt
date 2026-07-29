/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.utils.storage

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class MediaScanOptions(
  val includeNoMediaFolders: Boolean = false,
  val includeAudio: Boolean = false,
  val minimumAudioDurationSeconds: Int = 0,
) {
  val excludeNoMediaFolders: Boolean
    get() = !includeNoMediaFolders

  val cacheKey: String
    get() =
      "includeNoMedia=$includeNoMediaFolders|includeAudio=$includeAudio|minAudio=$minimumAudioDurationSeconds"

  fun includesAudioDuration(durationMs: Long): Boolean =
    minimumAudioDurationSeconds == 0 || durationMs >= minimumAudioDurationSeconds * 1000L
}

class NoMediaPathFilter(
  private val options: MediaScanOptions,
) {
  private val exclusionCache = ConcurrentHashMap<String, Boolean>()

  fun shouldExcludeDirectory(directory: File?): Boolean {
    if (!options.excludeNoMediaFolders || directory == null) {
      return false
    }

    // App media inside Android/data is often hidden behind .nomedia, but users still
    // expect those video folders to be discoverable in the browser.
    if (isAndroidDataAccessiblePath(directory)) {
      return false
    }

    return hasNoMediaMarkerInPath(directory)
  }

  fun shouldExcludeFile(file: File): Boolean = shouldExcludeDirectory(file.parentFile)

  private fun hasNoMediaMarkerInPath(directory: File): Boolean {
    val path = runCatching { directory.absolutePath }.getOrElse { return false }
    exclusionCache[path]?.let { return it }

    val result =
      runCatching {
        File(directory, ".nomedia").exists() ||
          directory.parentFile?.let(::hasNoMediaMarkerInPath) == true
      }.getOrElse { error ->
        Log.w(TAG, "Failed checking .nomedia ancestry for $path", error)
        false
      }

    exclusionCache[path] = result
    return result
  }

  private companion object {
    const val TAG = "NoMediaPathFilter"
  }
}
