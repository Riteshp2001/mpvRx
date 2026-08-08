/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import java.net.URI

/**
 * A normalized path below a configured network-connection root.
 *
 * The persisted connection path is the root (an FTP/WebDAV directory or SMB share). Values of
 * this type never contain a scheme, authority, credentials, or parent traversal. The display form
 * is rooted (`/Movies/video.mkv`) while [relative] is suitable for protocol client APIs.
 */
@JvmInline
value class NetworkPath private constructor(
  val value: String,
) {
  val isRoot: Boolean
    get() = value == ROOT_VALUE

  val segments: List<String>
    get() = if (isRoot) emptyList() else value.removePrefix(ROOT_VALUE).split('/')

  val relative: String
    get() = value.removePrefix(ROOT_VALUE)

  fun child(name: String): NetworkPath {
    validateSegment(name)
    val joined = if (isRoot) name else "$relative/$name"
    return from(joined)
  }

  override fun toString(): String = value

  companion object {
    private const val ROOT_VALUE = "/"
    private const val MAX_PATH_CHARS = 32_768
    private const val MAX_SEGMENT_CHARS = 1_024
    private const val MAX_SEGMENTS = 512

    val ROOT = NetworkPath(ROOT_VALUE)

    /**
     * Parses a user/client supplied path without URL-decoding it. `+`, `%`, `#`, and `?` therefore
     * remain ordinary filename characters and are encoded only when a protocol builds a URL.
     */
    fun from(rawPath: String): NetworkPath {
      require(!rawPath.contains("://")) { "A network path must not contain a URI scheme" }
      require(rawPath.length <= MAX_PATH_CHARS) { "Network path is too long" }

      val segments =
        rawPath
          .split('/')
          .filter { it.isNotEmpty() }

      require(segments.size <= MAX_SEGMENTS) { "Network path has too many segments" }
      segments.forEach(::validateSegment)

      return if (segments.isEmpty()) ROOT else NetworkPath("/${segments.joinToString("/")}")
    }

    private fun validateSegment(segment: String) {
      require(segment.isNotEmpty()) { "A network path segment must not be empty" }
      require(segment.length <= MAX_SEGMENT_CHARS) { "Network path segment is too long" }
      require(segment != "." && segment != "..") { "A network path must not contain dot segments" }
      require('/' !in segment) { "A network path segment must not contain '/'" }
      require('\\' !in segment) { "A network path segment must not contain '\\'" }
      require(segment.none { it == '\u0000' || it.code in 1..31 || it.code == 127 }) {
        "A network path must not contain control characters"
      }
    }
  }
}

/**
 * Credential-free, persistent reference to a file below a saved network connection.
 *
 * The URI contains only the Room connection id and a normalized relative path. It can therefore
 * live in playlists and intents without copying passwords or short-lived loopback proxy tokens.
 */
object NetworkPlaybackUri {
  const val SCHEME = "mpvrx-network"

  data class Reference(
    val connectionId: Long,
    val path: NetworkPath,
  )

  fun create(
    connectionId: Long,
    path: String,
  ): String {
    require(connectionId > 0L) { "A saved network connection is required" }
    val normalizedPath = NetworkPath.from(path)
    return URI(SCHEME, connectionId.toString(), normalizedPath.value, null, null).toASCIIString()
  }

  fun parse(rawUri: String): Reference? =
    runCatching {
      val uri = URI(rawUri)
      if (!uri.scheme.equals(SCHEME, ignoreCase = true) ||
        uri.userInfo != null ||
        uri.port != -1 ||
        uri.query != null ||
        uri.fragment != null
      ) {
        return@runCatching null
      }

      val connectionId = uri.authority?.toLongOrNull()?.takeIf { it > 0L } ?: return@runCatching null
      Reference(connectionId, NetworkPath.from(uri.path.orEmpty()))
    }.getOrNull()
}
