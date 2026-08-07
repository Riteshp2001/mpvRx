/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.net.URI

object M3uPlaybackPolicy {
  private val networkSchemes =
    setOf("http", "https", "ftp", "ftps", "rtmp", "rtmps", "rtsp", "rtsps", "mms", "mmsh")
  private val m3uMimeTypes =
    setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-mpegurl", "video/x-mpegurl")

  fun shouldExpandInApp(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
    hasExistingPlaylist: Boolean,
    hasPlaylistId: Boolean,
  ): Boolean {
    if (hasExistingPlaylist || hasPlaylistId) return false
    if (!looksLikeM3uForPlayback(playableUri, originalUri, fileName, mimeType)) return false

    // Remote M3U/HLS URLs often need mpv's own HTTP stack, ytdl hook, cookies,
    // headers, redirects, and stream-specific playlist handling.
    // For IPTV-style links, we should still expand them in-app even when they are remote.
    return true
  }

  internal fun looksLikeM3uForPlayback(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
  ): Boolean {
    val candidates = listOfNotNull(playableUri, originalUri, fileName).map { it.lowercase() }
    return candidates.any(::hasM3uMarker) ||
      mimeType?.lowercase()?.let { type ->
        type.contains("mpegurl") || type.contains("x-mpegurl") || type.contains("vnd.apple.mpegurl")
      } == true ||
      mimeType?.lowercase()?.let { type ->
        m3uMimeTypes.contains(type)
      } == true
  }

  private fun hasM3uMarker(value: String): Boolean {
    val uriParts =
      runCatching { URI(value) }
        .map { uri -> listOfNotNull(uri.rawPath, uri.rawQuery, uri.rawFragment) }
        .getOrDefault(
          listOf(
            value.substringBefore('?').substringBefore('#'),
            value.substringAfter('?', "").substringBefore('#'),
            value.substringAfter('#', ""),
          ),
        )

    return uriParts.any { part ->
      val lowerPart = part.lowercase()
      lowerPart.endsWith(".m3u") ||
        lowerPart.endsWith(".m3u8") ||
        lowerPart.contains(".m3u?") ||
        lowerPart.contains(".m3u8?") ||
        lowerPart.contains(".m3u#") ||
        lowerPart.contains(".m3u8#") ||
        lowerPart.contains(".m3u&") ||
        lowerPart.contains(".m3u8&") ||
        lowerPart.contains("=m3u") ||
        lowerPart.contains("=m3u8")
    }
  }

  private fun isNetworkUri(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val scheme = value.substringBefore(":", missingDelimiterValue = "").lowercase()
    return scheme in networkSchemes
  }
}
