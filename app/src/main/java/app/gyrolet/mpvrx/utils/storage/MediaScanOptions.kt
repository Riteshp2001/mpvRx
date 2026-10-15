/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.storage

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class MediaScanOptions(
  val includeNoMediaFolders: Boolean = false,
  val includeDotFolders: Boolean = includeNoMediaFolders,
  val hiddenFolderMarkerNames: Set<String> = setOf(".nomedia"),
  val ignoredHiddenMarkerNames: Set<String> = emptySet(),
  val includeAudio: Boolean = false,
  val minimumAudioDurationSeconds: Int = 0,
) {
  val normalizedHiddenFolderMarkerNames: Set<String> =
    hiddenFolderMarkerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()

  val activeHiddenMarkerNames: Set<String> =
    if (includeNoMediaFolders) normalizedHiddenFolderMarkerNames -
      ignoredHiddenMarkerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()
    else emptySet()

  val scanHiddenFolders: Boolean
    get() = includeNoMediaFolders || includeDotFolders

  val excludeNoMediaFolders: Boolean
    get() = !includeNoMediaFolders

  val cacheKey: String
    get() {
      val markers = normalizedHiddenFolderMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      val active = activeHiddenMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      return "hiddenScanV5=$includeNoMediaFolders|dot=$includeDotFolders|markers=$markers|active=$active|" +
        "includeAudio=$includeAudio|minAudio=$minimumAudioDurationSeconds"
    }

  val rootDiscoveryCacheKey: String
    get() {
      val markers = normalizedHiddenFolderMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      val active = activeHiddenMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      return "hiddenRootDiscoveryV3|marked=$includeNoMediaFolders|dot=$includeDotFolders|markers=$markers|active=$active"
    }

  fun includesAudioDuration(durationMs: Long): Boolean =
    minimumAudioDurationSeconds == 0 || durationMs >= minimumAudioDurationSeconds * 1000L
}

class NoMediaPathFilter(
  private val options: MediaScanOptions,
) {
  private val exclusionCache = ConcurrentHashMap<String, Boolean>()

  fun shouldExcludeDirectory(directory: File?): Boolean {
    if (directory == null) return false

    return hasHiddenMarkerInPath(directory)
  }

  fun shouldExcludeFile(file: File): Boolean =
    (file.name.startsWith(".") && !options.includeDotFolders) || shouldExcludeDirectory(file.parentFile)

  private fun hasHiddenMarkerInPath(directory: File): Boolean {
    val path = runCatching { directory.absolutePath }.getOrElse { return false }
    exclusionCache[path]?.let { return it }

    val result =
      runCatching {
        (directory.name.startsWith(".") && !options.includeDotFolders) ||
          (options.normalizedHiddenFolderMarkerNames
            .minus(options.activeHiddenMarkerNames)
            .any { File(directory, it).isFile }) ||
          (!options.scanHiddenFolders &&
            File(directory, app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.AUDIOBOOK_MARKER).isFile) ||
          directory.parentFile?.let(::hasHiddenMarkerInPath) == true
      }.getOrElse { error ->
        Log.w(TAG, "Failed checking hidden-folder ancestry for $path", error)
        false
      }

    exclusionCache[path] = result
    return result
  }

  private companion object {
    const val TAG = "NoMediaPathFilter"
  }
}

internal fun normalizeHiddenMarkerName(value: String): String? {
  val name = value.trim()
  if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) return null
  return if (name.startsWith('.')) name else ".$name"
}
