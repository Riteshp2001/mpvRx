// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import android.net.Uri
import app.gyrolet.mpvrx.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun isLocalArtworkSource(source: String?): Boolean {
  if (source.isNullOrBlank()) return false
  return Uri.parse(source).scheme.let { it == null || it == "file" || it == "content" }
}

/** Playback, widgets and local music use the same generator and cache as browser thumbnails. */
internal suspend fun ThumbnailRepository.getArtwork(
  source: String?,
  artworkUri: String? = null,
  isAudio: Boolean = true,
  widthPx: Int = 960,
  heightPx: Int = widthPx,
): Bitmap? =
  withContext(Dispatchers.IO) {
    if (!isLocalArtworkSource(source)) {
      return@withContext EmbeddedArtworkResolver.decodeArtworkUri(artworkContext, artworkUri)
    }
    val raw = checkNotNull(source)
    val parsed = Uri.parse(raw)
    val path = if (parsed.scheme == "file") parsed.path ?: raw else raw
    val uri = if (parsed.scheme == null) Uri.fromFile(File(path)) else parsed
    val name = uri.lastPathSegment.orEmpty()
    getThumbnail(
      Video(
        id = 0L,
        title = name,
        displayName = name,
        path = path,
        uri = uri,
        duration = 0L,
        durationFormatted = "",
        size = 0L,
        sizeFormatted = "",
        dateModified = 0L,
        dateAdded = 0L,
        mimeType = if (isAudio) "audio/*" else "video/*",
        bucketId = "",
        bucketDisplayName = "",
        width = 0,
        height = 0,
        fps = 0f,
        resolution = "",
        isAudio = isAudio,
      ),
      widthPx,
      heightPx,
    )
  }
