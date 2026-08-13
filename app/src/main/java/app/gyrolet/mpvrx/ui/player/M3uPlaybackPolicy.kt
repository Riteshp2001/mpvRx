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
  private val m3uMimeTypes =
    setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-mpegurl", "video/x-mpegurl")
  private val hlsMimeTypes = setOf("application/vnd.apple.mpegurl")

  fun shouldExpandInApp(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
    hasExistingPlaylist: Boolean,
    hasPlaylistId: Boolean,
  ): Boolean {
    if (hasExistingPlaylist || hasPlaylistId) return false

    // HLS is a native libmpv/libavformat streaming format. Do not pre-download an .m3u8 manifest
    // just to discover #EXT-X-* and then request the same signed/tokenized URL a second time.
    // Ordinary .m3u IPTV playlists still use MpvRx's in-app expansion below.
    if (looksLikeHlsForDirectPlayback(playableUri, originalUri, fileName, mimeType)) return false

    return looksLikeM3uForPlayback(playableUri, originalUri, fileName, mimeType)
  }

  internal fun looksLikeM3uForPlayback(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
  ): Boolean {
    val candidates = listOfNotNull(playableUri, originalUri, fileName).map { it.lowercase() }
    return candidates.any(::hasM3uMarker) ||
      mimeType?.substringBefore(';')?.trim()?.lowercase()?.let(m3uMimeTypes::contains) == true
  }

  internal fun looksLikeHlsForDirectPlayback(
    playableUri: String,
    originalUri: String?,
    fileName: String,
    mimeType: String?,
  ): Boolean {
    val candidates = listOfNotNull(playableUri, originalUri, fileName).map { it.lowercase() }
    if (candidates.any(::hasM3u8Marker)) return true

    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return normalizedMimeType in hlsMimeTypes
  }

  private fun hasM3uMarker(value: String): Boolean =
    uriParts(value).any { part ->
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

  private fun hasM3u8Marker(value: String): Boolean =
    uriParts(value).any { part ->
      val lowerPart = part.lowercase()
      lowerPart.endsWith(".m3u8") ||
        lowerPart.contains(".m3u8?") ||
        lowerPart.contains(".m3u8#") ||
        lowerPart.contains(".m3u8&") ||
        lowerPart.contains("=m3u8")
    }

  private fun uriParts(value: String): List<String> =
    runCatching { URI(value) }
      .map { uri -> listOfNotNull(uri.rawPath, uri.rawQuery, uri.rawFragment) }
      .getOrDefault(
        listOf(
          value.substringBefore('?').substringBefore('#'),
          value.substringAfter('?', "").substringBefore('#'),
          value.substringAfter('#', ""),
        ),
      )
}
