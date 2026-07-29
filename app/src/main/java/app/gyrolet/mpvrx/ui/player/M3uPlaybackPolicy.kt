/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player

import java.net.URI

object M3uPlaybackPolicy {
  private val networkSchemes =
    setOf("http", "https", "ftp", "ftps", "rtmp", "rtmps", "rtsp", "rtsps", "mms", "mmsh")

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
    return !isNetworkUri(originalUri) && !isNetworkUri(playableUri)
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
