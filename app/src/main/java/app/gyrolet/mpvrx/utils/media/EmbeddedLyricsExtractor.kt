/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object EmbeddedLyricsExtractor {
  private const val TAG = "EmbeddedLyricsExtractor"

  suspend fun extractEmbeddedLyrics(
    context: Context,
    mediaPath: String?,
  ): Lyrics? = withContext(Dispatchers.IO) {
    if (mediaPath.isNullOrBlank()) return@withContext null
    currentCoroutineContext().ensureActive()
    val sourceUri = Uri.parse(mediaPath)
    val scheme = sourceUri.scheme
    val isLocalSource =
      scheme.isNullOrBlank() || scheme.equals("file", ignoreCase = true) ||
        scheme.equals("content", ignoreCase = true) || scheme.equals("android.resource", ignoreCase = true)

    // 1. Try reading local .lrc file next to audio file first
    val localLrcLyrics = if (isLocalSource) findLocalLrcFile(mediaPath) else null
    if (localLrcLyrics != null && localLrcLyrics.isValid()) {
      Log.d(TAG, "Found local .lrc file for: $mediaPath")
      return@withContext localLrcLyrics
    }

    // 2. Try MPV tag properties for embedded lyrics
    val mpvLyrics = findLyricsInMpvMetadata()
    if (mpvLyrics != null) {
      val parsed = LyricsUtils.parseLyrics(mpvLyrics, sourceType = LyricsSourceType.EMBEDDED)
      if (parsed.isValid()) {
        Log.d(TAG, "Extracted embedded lyrics via MPV metadata tags")
        return@withContext parsed
      }
    }

    if (!isLocalSource) return@withContext null
    currentCoroutineContext().ensureActive()

    // 3. Fallback: Direct ID3v2 parser from media file / content stream
    val id3Lyrics = findLyricsDirectlyFromMedia(context, mediaPath)
    if (id3Lyrics != null) {
      val parsed = LyricsUtils.parseLyrics(id3Lyrics, sourceType = LyricsSourceType.EMBEDDED)
      if (parsed.isValid()) {
        Log.d(TAG, "Extracted embedded lyrics via direct ID3 stream parser")
        return@withContext parsed
      }
    }

    // 4. Fallback to MediaMetadataRetriever (for supported platforms)
    currentCoroutineContext().ensureActive()
    runCatching {
      val retriever = MediaMetadataRetriever()
      val rawLyrics = try {
        when {
          scheme.isNullOrBlank() -> retriever.setDataSource(mediaPath)
          scheme.equals("file", ignoreCase = true) -> retriever.setDataSource(sourceUri.path)
          else -> retriever.setDataSource(context, sourceUri)
        }
        // Key 1000 represents METADATA_KEY_LYRICS in vendor MediaMetadataRetriever extensions
        retriever.extractMetadata(1000)
      } finally {
        retriever.release()
      }

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

  private fun findLyricsInMpvMetadata(): String? {
    // 1. Dynamic scan through all MPV metadata entries
    val count = PlaybackSession.getPropertyInt("metadata/list/count") ?: 0
    for (i in 0 until count) {
      val key = PlaybackSession.getPropertyString("metadata/list/$i/key") ?: continue
      val keyLower = key.lowercase()
      if (
        keyLower.startsWith("lyrics") ||
        keyLower.contains("unsynced") ||
        keyLower.contains("synced") ||
        keyLower in setOf("uslt", "sylt", "ult", "©lyr", "clyr")
      ) {
        val value = PlaybackSession.getPropertyString("metadata/list/$i/value")?.takeIf { it.isNotBlank() }
        if (value != null) return value
      }
    }

    // 2. Direct lookup for common FFmpeg ID3/Vorbis/MP4 metadata tags
    val candidateKeys = listOf(
      "lyrics-eng", "lyrics-und", "lyrics-xxx", "lyrics-",
      "LYRICS", "lyrics", "Lyrics", "LYRICS_TEXT",
      "UNSYNCED LYRICS", "UNSYNCEDLYRICS", "unsyncedlyrics", "unsynced_lyrics",
      "SYNCED LYRICS", "SYNCEDLYRICS", "syncedlyrics", "synced_lyrics",
      "USLT", "SYLT", "ULT", "©lyr", "clyr", "TIT3",
    )
    return candidateKeys.firstNotNullOfOrNull { key ->
      PlaybackSession.getPropertyString("metadata/by-key/$key")?.takeIf { it.isNotBlank() }
    }
  }

  private fun findLyricsDirectlyFromMedia(context: Context, mediaPath: String): String? {
    return runCatching {
      val cleanPath = when {
        mediaPath.startsWith("file://") -> mediaPath.removePrefix("file://")
        mediaPath.startsWith("content://") -> null
        else -> mediaPath
      }
      val inputStream = if (cleanPath != null) {
        val file = File(cleanPath)
        if (!file.exists() || !file.canRead()) return null
        file.inputStream()
      } else {
        context.contentResolver.openInputStream(Uri.parse(mediaPath))
      }
      inputStream?.use { extractLyricsFromStream(it) }
    }.getOrNull()
  }

  private fun extractLyricsFromStream(inputStream: java.io.InputStream): String? {
    try {
      val header = ByteArray(10)
      if (inputStream.read(header) < 10) return null
      if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null

      val majorVersion = header[3].toInt() and 0xFF
      val tagFlags = header[5].toInt() and 0xFF
      val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
        ((header[7].toInt() and 0x7F) shl 14) or
        ((header[8].toInt() and 0x7F) shl 7) or
        (header[9].toInt() and 0x7F)

      if (tagSize <= 0 || tagSize > 10 * 1024 * 1024) return null

      val tagData = ByteArray(tagSize)
      var bytesRead = 0
      while (bytesRead < tagSize) {
        val count = inputStream.read(tagData, bytesRead, tagSize - bytesRead)
        if (count == -1) break
        bytesRead += count
      }
      if (bytesRead < tagSize) return null

      var offset = 0
      // Check for extended header in ID3v2.3/2.4
      if ((tagFlags and 0x40) != 0) {
        if (majorVersion == 4 && offset + 4 <= tagSize) {
          val extSize = ((tagData[offset].toInt() and 0x7F) shl 21) or
            ((tagData[offset + 1].toInt() and 0x7F) shl 14) or
            ((tagData[offset + 2].toInt() and 0x7F) shl 7) or
            (tagData[offset + 3].toInt() and 0x7F)
          offset += extSize
        } else if (majorVersion == 3 && offset + 4 <= tagSize) {
          val extSize = ((tagData[offset].toInt() and 0xFF) shl 24) or
            ((tagData[offset + 1].toInt() and 0xFF) shl 16) or
            ((tagData[offset + 2].toInt() and 0xFF) shl 8) or
            (tagData[offset + 3].toInt() and 0xFF)
          offset += extSize + 4
        }
      }

      while (offset + 10 <= tagSize) {
        val frameId = String(tagData, offset, if (majorVersion == 2) 3 else 4, Charsets.ISO_8859_1)
        if (frameId.all { it == '\u0000' }) break

        val (headerLen, frameSize) = if (majorVersion == 2) {
          val size = ((tagData[offset + 3].toInt() and 0xFF) shl 16) or
            ((tagData[offset + 4].toInt() and 0xFF) shl 8) or
            (tagData[offset + 5].toInt() and 0xFF)
          Pair(6, size)
        } else if (majorVersion == 4) {
          val size = ((tagData[offset + 4].toInt() and 0x7F) shl 21) or
            ((tagData[offset + 5].toInt() and 0x7F) shl 14) or
            ((tagData[offset + 6].toInt() and 0x7F) shl 7) or
            (tagData[offset + 7].toInt() and 0x7F)
          Pair(10, size)
        } else {
          // ID3v2.3
          val size = ((tagData[offset + 4].toInt() and 0xFF) shl 24) or
            ((tagData[offset + 5].toInt() and 0xFF) shl 16) or
            ((tagData[offset + 6].toInt() and 0xFF) shl 8) or
            (tagData[offset + 7].toInt() and 0xFF)
          Pair(10, size)
        }

        val frameStart = offset + headerLen
        val frameEnd = frameStart + frameSize
        if (frameEnd > tagSize || frameSize <= 0) break

        if (frameId == "USLT" || frameId == "ULT") {
          val encodingByte = tagData[frameStart].toInt() and 0xFF
          val (charset, nullTermSize) = when (encodingByte) {
            1 -> Pair(Charsets.UTF_16, 2)
            2 -> Pair(Charsets.UTF_16BE, 2)
            3 -> Pair(Charsets.UTF_8, 1)
            else -> Pair(Charsets.ISO_8859_1, 1)
          }

          // Skip 1 byte encoding + 3 bytes language
          var contentOffset = frameStart + 4
          // Skip descriptor (null-terminated string)
          while (contentOffset < frameEnd) {
            if (nullTermSize == 2) {
              if (contentOffset + 1 < frameEnd && tagData[contentOffset] == 0.toByte() && tagData[contentOffset + 1] == 0.toByte()) {
                contentOffset += 2
                break
              }
              contentOffset += 2
            } else {
              if (tagData[contentOffset] == 0.toByte()) {
                contentOffset += 1
                break
              }
              contentOffset += 1
            }
          }

          if (contentOffset < frameEnd) {
            val text = String(tagData, contentOffset, frameEnd - contentOffset, charset).trim().trim('\u0000')
            if (text.isNotBlank()) return text
          }
        }

        offset = frameEnd
      }
    } catch (e: Exception) {
      Log.d(TAG, "Error parsing ID3 tag: ${e.message}")
    }
    return null
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
