/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EmbeddedLyricsExtractor {
  private const val TAG = "EmbeddedLyricsExtractor"

  suspend fun extractEmbeddedLyrics(
    context: Context,
    mediaPath: String?,
  ): Lyrics? = withContext(Dispatchers.IO) {
    if (mediaPath.isNullOrBlank()) return@withContext null

    // 1. Try reading local .lrc file next to audio file first
    val localLrcLyrics = findLocalLrcFile(mediaPath)
    if (localLrcLyrics != null && localLrcLyrics.isValid()) {
      Log.d(TAG, "Found local .lrc file for: $mediaPath")
      return@withContext localLrcLyrics
    }

    // 2. Try MPV tag properties for embedded lyrics
    val mpvLyrics = listOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS", "USLT", "lyrics")
      .firstNotNullOfOrNull { key ->
        PlaybackSession.getPropertyString("metadata/by-key/$key")?.takeIf { it.isNotBlank() }
      }
    if (mpvLyrics != null) {
      val parsed = LyricsUtils.parseLyrics(mpvLyrics, sourceType = LyricsSourceType.EMBEDDED)
      if (parsed.isValid()) {
        Log.d(TAG, "Extracted embedded lyrics via MPV metadata tags")
        return@withContext parsed
      }
    }

    // 3. Fallback to MediaMetadataRetriever (key 1000 for lyrics)
    runCatching {
      val retriever = MediaMetadataRetriever()
      val cleanPath = when {
        mediaPath.startsWith("file://") -> mediaPath.removePrefix("file://")
        mediaPath.startsWith("content://") -> null
        else -> mediaPath
      }

      if (cleanPath != null) {
        retriever.setDataSource(cleanPath)
      } else {
        retriever.setDataSource(context, Uri.parse(mediaPath))
      }

      // Key 1000 represents METADATA_KEY_LYRICS in MediaMetadataRetriever
      val rawLyrics = retriever.extractMetadata(1000)
      retriever.release()

      if (!rawLyrics.isNullOrBlank()) {
        val parsed = LyricsUtils.parseLyrics(rawLyrics, sourceType = LyricsSourceType.EMBEDDED)
        if (parsed.isValid()) {
          Log.d(TAG, "Extracted embedded lyrics via MediaMetadataRetriever")
          return@withContext parsed
        }
      }
    }.onFailure {
      Log.d(TAG, "MediaMetadataRetriever lyrics extraction failed: ${it.message}")
    }

    null
  }

  private fun findLocalLrcFile(mediaPath: String): Lyrics? {
    return try {
      val cleanPath = mediaPath.removePrefix("file://")
      val audioFile = File(cleanPath)
      if (!audioFile.exists()) return null

      val parentDir = audioFile.parentFile ?: return null
      val nameWithoutExt = audioFile.nameWithoutExtension

      val lrcFile = File(parentDir, "$nameWithoutExt.lrc")
      if (lrcFile.exists() && lrcFile.canRead()) {
        val content = lrcFile.readText()
        if (content.isNotBlank()) {
          val parsed = LyricsUtils.parseLyrics(content, sourceType = LyricsSourceType.LOCAL)
          if (parsed.isValid()) return parsed
        }
      }

      null
    } catch (e: Exception) {
      Log.d(TAG, "Error checking local .lrc file: ${e.message}")
      null
    }
  }
}
