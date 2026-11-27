/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.medialibrary

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils

/**
 * Persisted copy of the media library list, so a cold boot can show it immediately.
 *
 * MediaLibraryViewModel otherwise re-queries MediaStore once per folder on every launch. That is the
 * slowest of the three browser modes and, unlike the folder list, it had no cache at all. This stores
 * the resolved list alongside the MediaStore generation it was built from; the generation is what
 * [app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard] later compares to decide whether the
 * list is still trustworthy.
 *
 * Format is a delimiter-separated string rather than JSON, matching FolderListViewModel's cache. The
 * separator is stripped from stored values on write so a title or path containing it cannot corrupt
 * the record.
 */
internal object MediaLibraryCache {
  private const val TAG = "MediaLibraryCache"
  private const val PREFS = "media_library_cache"

  // Rescan snapshots written before the standard MediaStore orientation column was used.
  private const val KEY_VIDEOS = "videos_v3"
  private const val KEY_SCAN_OPTIONS = "scan_options_v3"
  private const val KEY_GENERATION = "generation_v3"
  private const val KEY_METADATA = "metadata_v3"
  private const val KEY_METADATA_VERSION = "metadata_version"

  // Unreadable snapshots from other encodings (v4 was the reverted nullable-rotation format). They
  // would otherwise be parsed into memory with this preferences file on every cold start.
  private val OBSOLETE_KEYS =
    listOf(2, 4).flatMap { version ->
      listOf("videos_v$version", "scan_options_v$version", "generation_v$version", "metadata_v$version")
    }

  // ASCII unit/record separators, escaped rather than embedded as literal control characters
  // so the source stays readable. Both are pathological in a filename.
  private const val FIELD_SEPARATOR = "\u001F"
  internal const val RECORD_SEPARATOR = "\u001E"
  private const val FIELD_COUNT = 23

  /**
   * Fields written per video, in order. Mirrors the [Video] constructor; anything not listed here is
   * reconstructed on read.
   */
  internal fun encode(video: Video): String =
    listOf(
      video.id.toString(),
      video.title,
      video.displayName,
      video.path,
      video.uri.toString(),
      video.duration.toString(),
      video.size.toString(),
      video.dateModified.toString(),
      video.dateAdded.toString(),
      video.mimeType,
      video.bucketId,
      video.bucketDisplayName,
      video.resolution,
      video.isAudio.toString(),
      video.width.toString(),
      video.height.toString(),
      video.rotation.toString(),
      video.fps.toString(),
      video.hasEmbeddedSubtitles.toString(),
      video.subtitleCodec,
      video.videoCodec,
      video.videoCodecMimeType,
      video.artworkUrl.orEmpty(),
    ).joinToString(FIELD_SEPARATOR) { sanitize(it) }

  /**
   * Every Library snapshot carries its own scan options and MediaStore generation, so another
   * browser mode cannot accidentally validate an unrelated list by recording a shared token.
   */
  fun save(
    context: Context,
    videos: List<Video>,
    scanOptionsKey: String,
    observedGeneration: String?,
    metadataEnriched: Boolean,
  ) {
    if (observedGeneration == null) return // No safe cold-boot shortcut below API 30.
    runCatching {
      context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_VIDEOS, videos.joinToString(RECORD_SEPARATOR) { encode(it) })
        .putString(KEY_SCAN_OPTIONS, scanOptionsKey)
        .putString(KEY_GENERATION, observedGeneration)
        .putBoolean(KEY_METADATA, metadataEnriched)
        .putInt(KEY_METADATA_VERSION, MediaInfoOps.BASIC_METADATA_VERSION)
        .also { editor -> OBSOLETE_KEYS.forEach(editor::remove) }
        .apply()
    }.onFailure { error ->
      Log.w(TAG, "Could not persist media library cache", error)
    }
  }

  /** Restore only a complete snapshot for the exact active scanning policy. */
  fun load(
    context: Context,
    scanOptionsKey: String,
    requireMetadata: Boolean,
  ): List<Video>? =
    runCatching {
      val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      if (prefs.getString(KEY_SCAN_OPTIONS, null) != scanOptionsKey) return null
      if (requireMetadata && !prefs.getBoolean(KEY_METADATA, false)) return null
      if (requireMetadata && prefs.getInt(KEY_METADATA_VERSION, 0) != MediaInfoOps.BASIC_METADATA_VERSION) return null
      val snapshotGeneration = prefs.getString(KEY_GENERATION, null) ?: return null
      if (!MediaStoreGenerationGuard.isTokenCurrent(context, snapshotGeneration)) return null
      val raw = prefs.getString(KEY_VIDEOS, null) ?: return null
      if (raw.isBlank()) return null
      val records = raw.split(RECORD_SEPARATOR)
      val videos = records.mapNotNull(::decode)
      // A partial decode must never silently hide some of the user's videos.
      videos.takeIf { it.size == records.size && it.isNotEmpty() }
    }.onFailure { error ->
      Log.w(TAG, "Could not read media library cache", error)
    }.getOrNull()

  fun clear(context: Context) {
    runCatching {
      context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_VIDEOS)
        .remove(KEY_SCAN_OPTIONS)
        .remove(KEY_GENERATION)
        .remove(KEY_METADATA)
        .remove(KEY_METADATA_VERSION)
        .also { editor -> OBSOLETE_KEYS.forEach(editor::remove) }
        .apply()
    }.onFailure { error ->
      Log.w(TAG, "Could not clear media library cache", error)
    }
  }

  internal fun decode(record: String): Video? {
    val parts = record.split(FIELD_SEPARATOR)
    if (parts.size != FIELD_COUNT) return null
    val id = parts[0].toLongOrNull() ?: return null
    val duration = parts[5].toLongOrNull() ?: return null
    val size = parts[6].toLongOrNull() ?: return null
    val dateModified = parts[7].toLongOrNull() ?: return null
    val dateAdded = parts[8].toLongOrNull() ?: return null
    val isAudio = parts[13].toBooleanStrictOrNull() ?: return null
    val width = parts[14].toIntOrNull() ?: return null
    val height = parts[15].toIntOrNull() ?: return null
    val rotation = parts[16].toIntOrNull() ?: return null
    val fps = parts[17].toFloatOrNull() ?: return null
    val hasEmbeddedSubtitles = parts[18].toBooleanStrictOrNull() ?: return null

    return Video(
      id = id,
      title = parts[1],
      displayName = parts[2],
      path = parts[3],
      uri = Uri.parse(parts[4]),
      duration = duration,
      durationFormatted = if (duration > 0L) VideoScanUtils.formatDuration(duration) else "",
      size = size,
      sizeFormatted = MediaUtils.formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateAdded,
      mimeType = parts[9],
      bucketId = parts[10],
      bucketDisplayName = parts[11],
      width = width,
      height = height,
      rotation = rotation,
      fps = fps,
      resolution = parts[12],
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = parts[19],
      videoCodec = parts[20],
      videoCodecMimeType = parts[21],
      isAudio = isAudio,
      artworkUrl = parts[22].ifBlank { null },
    )
  }

  private fun sanitize(value: String): String =
    value
      .replace(FIELD_SEPARATOR, " ")
      .replace(RECORD_SEPARATOR, " ")
      .replace('\n', ' ')
      .replace('\r', ' ')
}
