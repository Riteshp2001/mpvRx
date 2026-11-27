/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.storage

import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class MediaScanOptions(
  val includeNoMediaFolders: Boolean = false,
  val includeDotFolders: Boolean = includeNoMediaFolders,
  val hiddenFolderMarkerNames: Set<String> = setOf(".nomedia"),
  val ignoredHiddenMarkerNames: Set<String> = emptySet(),
  val includeAudio: Boolean = false,
  val minimumAudioDurationSeconds: Int = 0,
  val minimumVideoDurationSeconds: Int = 0,
  val minimumMediaFileSizeBytes: Long = 0L,
  val whitelistEnabled: Boolean = false,
  val whitelistedPaths: Set<String> = emptySet(),
) {
  val normalizedHiddenFolderMarkerNames: Set<String> =
    hiddenFolderMarkerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()

  val activeHiddenMarkerNames: Set<String> =
    if (includeNoMediaFolders) {
      normalizedHiddenFolderMarkerNames -
        ignoredHiddenMarkerNames.mapNotNull(::normalizeHiddenMarkerName).toSet()
    } else {
      emptySet()
    }

  val scanHiddenFolders: Boolean
    get() = activeHiddenMarkerNames.isNotEmpty() || includeDotFolders

  val excludeNoMediaFolders: Boolean
    get() = !includeNoMediaFolders

  val cacheKey: String
    get() {
      val markers = normalizedHiddenFolderMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      val active = activeHiddenMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      return "hiddenScanV5=$includeNoMediaFolders|dot=$includeDotFolders|markers=$markers|active=$active|" +
        "includeAudio=$includeAudio|minAudio=$minimumAudioDurationSeconds|$mediaFilterKey"
    }

  /** The size, duration and whitelist part of [cacheKey], for caches that key the other options themselves. */
  val mediaFilterKey: String
    get() =
      "minVideo=$minimumVideoDurationSeconds|minSize=$minimumMediaFileSizeBytes|" +
        "whitelist=$whitelistEnabled|allowed=${whitelistedPaths.sorted().joinToString(",")}"

  val rootDiscoveryCacheKey: String
    get() {
      val markers = normalizedHiddenFolderMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      val active = activeHiddenMarkerNames.sorted().joinToString("") { "${it.length}:$it" }
      return "hiddenRootDiscoveryV3|marked=$includeNoMediaFolders|dot=$includeDotFolders" +
        "|markers=$markers|active=$active"
    }

  // One filter per options instance, so a single scan reuses its ancestor-marker cache.
  val pathFilter: NoMediaPathFilter by lazy { NoMediaPathFilter(this) }

  /** Missing duration and unknown size remain visible rather than falsely excluding media. */
  fun includesVideoDuration(durationMs: Long): Boolean =
    minimumVideoDurationSeconds <= 0 ||
      durationMs <= 0L ||
      durationMs >= minimumVideoDurationSeconds * 1000L

  fun includesSize(sizeBytes: Long): Boolean =
    minimumMediaFileSizeBytes <= 0L || sizeBytes <= 0L || sizeBytes >= minimumMediaFileSizeBytes

  /** Only a configured minimum makes a scan pay for decoding a file's duration. */
  fun filtersDuration(isAudio: Boolean): Boolean =
    if (isAudio) minimumAudioDurationSeconds > 0 else minimumVideoDurationSeconds > 0

  fun includesMedia(
    sizeBytes: Long,
    durationMs: Long,
    isAudio: Boolean = false,
  ): Boolean =
    includesSize(sizeBytes) &&
      (if (isAudio) includesAudioDuration(durationMs) else includesVideoDuration(durationMs))

  fun includesPath(path: String): Boolean = isWhitelistedMediaPath(path, whitelistEnabled, whitelistedPaths)

  fun includesVideo(video: Video): Boolean =
    includesPath(video.bucketId) && includesMedia(video.size, video.duration, video.isAudio)

  fun includesAudioDuration(durationMs: Long): Boolean =
    durationMs <= 0L ||
      minimumAudioDurationSeconds == 0 ||
      durationMs >= minimumAudioDurationSeconds * 1000L
}

/** A disabled or empty whitelist restricts nothing, so removing its last folder never hides the library. */
internal fun isWhitelistedMediaPath(
  path: String,
  enabled: Boolean,
  whitelist: Set<String>,
): Boolean = !enabled || whitelist.isEmpty() || whitelist.any { isMediaPathInFolder(path, it) }

class NoMediaPathFilter(
  private val options: MediaScanOptions,
) {
  private val exclusionCache = ConcurrentHashMap<String, Boolean>()
  private val excludedMarkerNames = options.normalizedHiddenFolderMarkerNames - options.activeHiddenMarkerNames

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
          excludedMarkerNames.any { File(directory, it).isFile } ||
          (
            !options.scanHiddenFolders &&
              File(directory, app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.AUDIOBOOK_MARKER).isFile
          ) ||
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
