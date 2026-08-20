/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.recentlyplayed

/** Pure source classification shared by history loading, validation, and deletion. */
object RecentlyPlayedMediaClassifier {
  private val remoteSchemes =
    setOf(
      "http",
      "https",
      "rtmp",
      "rtmps",
      "rtsp",
      "ftp",
      "mms",
      "mpvrx-network",
    )

  private val genericStreamNames =
    setOf(
      "stream",
      "stream.mkv",
      "stream.mp4",
      "stream.ts",
      "stream.webm",
      "stream.avi",
    )

  fun isRemote(path: String): Boolean = sourceScheme(path) in remoteSchemes

  fun isContentUri(path: String): Boolean = sourceScheme(path) == "content"

  fun isFileUri(path: String): Boolean = sourceScheme(path) == "file"

  fun isGenericStreamName(name: String?): Boolean =
    name.isNullOrBlank() || name.trim().lowercase() in genericStreamNames

  /** History intentionally excludes playlist containers rather than presenting them as videos. */
  fun isStreamingPlaylist(path: String): Boolean {
    val normalized = path.lowercase()
    if (normalized.endsWith(".m3u") ||
      normalized.endsWith(".m3u8") ||
      normalized.endsWith(".mpd")
    ) {
      return true
    }

    if (normalized.contains("playlist") || normalized.contains("manifest")) return true

    if (normalized.contains("index") &&
      (
        normalized.contains(".m3u") ||
          normalized.contains("hls") ||
          normalized.contains("dash") ||
          normalized.contains("mpd")
      )
    ) {
      return true
    }

    return normalized.contains("iptv") ||
      (normalized.contains("channel") && normalized.contains("stream"))
  }

  private fun sourceScheme(path: String): String? {
    val separator = path.indexOf(':')
    if (separator <= 0) return null

    val candidate = path.substring(0, separator)
    if (!candidate.first().isLetter() ||
      candidate.drop(1).any { !it.isLetterOrDigit() && it !in "+-." }
    ) {
      return null
    }

    // A single-letter prefix followed by a slash is a Windows drive, not a URI scheme.
    val nextCharacter = path.getOrNull(separator + 1)
    if (candidate.length == 1 && (nextCharacter == '/' || nextCharacter == '\\')) return null
    return candidate.lowercase()
  }
}
