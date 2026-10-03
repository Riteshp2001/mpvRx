/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.medialibrary

import android.content.Context
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.domain.media.model.Video
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The last library listing this mode produced, kept so entering the mode does not have to re-derive
 * it.
 *
 * Stored as a file rather than in [android.content.SharedPreferences] because this is one record
 * per video, not one per folder: a large library is megabytes of JSON, and preferences rewrite
 * their whole backing file on every edit. [CACHE_DIR] is the right home for it precisely because
 * losing it only costs the scan that would have happened anyway.
 */
object MediaLibrarySnapshot {
  private const val TAG = "MediaLibrarySnapshot"

  private const val CACHE_DIR = "media_library"
  private const val FILE_PREFIX = "library_"
  private const val FILE_SUFFIX = ".json"

  private val json = Json { ignoreUnknownKeys = true }

  /** The listing for [cacheKey], or an empty list when there is none worth trusting. */
  fun read(
    context: Context,
    cacheKey: String,
  ): List<Video> {
    val file = fileFor(context, cacheKey)
    if (!file.isFile) return emptyList()
    return try {
      json.decodeFromString<List<VideoSnapshot>>(file.readText()).map { it.toVideo() }
    } catch (error: Exception) {
      // A truncated or older-shaped file is a cache miss, not a failure: the caller scans instead.
      Log.w(TAG, "Discarding unreadable library snapshot ${file.name}", error)
      file.delete()
      emptyList()
    }
  }

  fun write(
    context: Context,
    cacheKey: String,
    videos: List<Video>,
  ) {
    val dir = File(context.cacheDir, CACHE_DIR)
    try {
      if (!dir.isDirectory && !dir.mkdirs()) {
        Log.w(TAG, "Unable to create ${dir.absolutePath} for the library snapshot")
        return
      }
      val target = fileFor(context, cacheKey)
      // Written beside the target and renamed over it, so a process death mid-write leaves the
      // previous snapshot intact instead of a half-written file that reads as corrupt.
      val pending = File(dir, target.name + ".pending")
      pending.writeText(json.encodeToString(videos.map { it.toSnapshot() }))
      if (!pending.renameTo(target)) {
        pending.delete()
        Log.w(TAG, "Unable to replace ${target.name}")
        return
      }
      // Options the user no longer has leave their listing behind; only one set is ever read.
      dir.listFiles { file -> file.name.startsWith(FILE_PREFIX) && file.name != target.name }
        ?.forEach { it.delete() }
    } catch (error: Exception) {
      Log.w(TAG, "Unable to save the library snapshot", error)
    }
  }

  private fun fileFor(
    context: Context,
    cacheKey: String,
  ): File =
    File(
      File(context.cacheDir, CACHE_DIR),
      // Hashed because the key itself carries separators that have no business in a file name.
      "$FILE_PREFIX${cacheKey.hashCode().toUInt().toString(16)}$FILE_SUFFIX",
    )
}

/**
 * [Video] carries a [Uri] and is built by the scanner, so the snapshot is a plain-data mirror of it
 * rather than the model itself.
 */
@Serializable
private data class VideoSnapshot(
  val id: Long,
  val title: String,
  val displayName: String,
  val path: String,
  val uri: String,
  val duration: Long,
  val durationFormatted: String,
  val size: Long,
  val sizeFormatted: String,
  val dateModified: Long,
  val dateAdded: Long,
  val mimeType: String,
  val bucketId: String,
  val bucketDisplayName: String,
  val width: Int,
  val height: Int,
  val fps: Float,
  val resolution: String,
  val hasEmbeddedSubtitles: Boolean,
  val subtitleCodec: String,
  val videoCodec: String,
  val videoCodecMimeType: String,
  val isAudio: Boolean,
  val artworkUrl: String? = null,
) {
  fun toVideo(): Video =
    Video(
      id = id,
      title = title,
      displayName = displayName,
      path = path,
      uri = Uri.parse(uri),
      duration = duration,
      durationFormatted = durationFormatted,
      size = size,
      sizeFormatted = sizeFormatted,
      dateModified = dateModified,
      dateAdded = dateAdded,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = resolution,
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
      videoCodec = videoCodec,
      videoCodecMimeType = videoCodecMimeType,
      isAudio = isAudio,
      artworkUrl = artworkUrl,
    )
}

private fun Video.toSnapshot(): VideoSnapshot =
  VideoSnapshot(
    id = id,
    title = title,
    displayName = displayName,
    path = path,
    uri = uri.toString(),
    duration = duration,
    durationFormatted = durationFormatted,
    size = size,
    sizeFormatted = sizeFormatted,
    dateModified = dateModified,
    dateAdded = dateAdded,
    mimeType = mimeType,
    bucketId = bucketId,
    bucketDisplayName = bucketDisplayName,
    width = width,
    height = height,
    fps = fps,
    resolution = resolution,
    hasEmbeddedSubtitles = hasEmbeddedSubtitles,
    subtitleCodec = subtitleCodec,
    videoCodec = videoCodec,
    videoCodecMimeType = videoCodecMimeType,
    isAudio = isAudio,
    artworkUrl = artworkUrl,
  )