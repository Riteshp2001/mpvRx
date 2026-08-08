/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.torrent

import java.net.URI

internal data class TorrentMetadataEndpoints(
  val trackers: List<String>,
  val webSeeds: List<String>,
)

/** Reads only connection endpoints from bounded .torrent metadata without retaining its tree. */
internal fun extractTorrentMetadataEndpoints(data: ByteArray): TorrentMetadataEndpoints =
  TorrentEndpointReader(data).read()

private class TorrentEndpointReader(
  private val data: ByteArray,
) {
  companion object {
    private const val MAX_DEPTH = 64
    private const val MAX_ENDPOINTS = 256
    private const val MAX_ENDPOINT_LENGTH = 4096
  }

  private val trackers = LinkedHashSet<String>()
  private val webSeeds = LinkedHashSet<String>()
  private var position = 0

  fun read(): TorrentMetadataEndpoints {
    expect('d')
    while (peek() != 'e') {
      val key = readString(maxDecodedLength = 64)
      when (key) {
        "announce" -> collectEndpointTree(trackers, setOf("http", "https", "udp"), 1)
        "announce-list" -> collectEndpointTree(trackers, setOf("http", "https", "udp"), 1)
        "url-list", "httpseeds" -> collectEndpointTree(webSeeds, setOf("http", "https"), 1)
        else -> skipValue(1)
      }
    }
    expect('e')
    if (position != data.size) throw IllegalArgumentException("Trailing torrent metadata")
    return TorrentMetadataEndpoints(trackers.toList(), webSeeds.toList())
  }

  private fun collectEndpointTree(
    destination: MutableSet<String>,
    allowedSchemes: Set<String>,
    depth: Int,
  ) {
    checkDepth(depth)
    when (peek()) {
      'l' -> {
        position++
        while (peek() != 'e') collectEndpointTree(destination, allowedSchemes, depth + 1)
        position++
      }
      in '0'..'9' -> {
        val value = readString(MAX_ENDPOINT_LENGTH) ?: return
        if (destination.size >= MAX_ENDPOINTS) return
        val endpoint = value.trim().takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return
        val scheme = runCatching { URI(endpoint).scheme?.lowercase() }.getOrNull()
        if (scheme in allowedSchemes) destination += endpoint
      }
      else -> skipValue(depth)
    }
  }

  private fun skipValue(depth: Int) {
    checkDepth(depth)
    when (peek()) {
      'i' -> {
        position++
        if (peek() == '-') position++
        var digits = 0
        while (peek() in '0'..'9') {
          position++
          digits++
        }
        if (digits == 0) throw IllegalArgumentException("Invalid bencoded integer")
        expect('e')
      }
      'l' -> {
        position++
        while (peek() != 'e') skipValue(depth + 1)
        position++
      }
      'd' -> {
        position++
        while (peek() != 'e') {
          skipString()
          skipValue(depth + 1)
        }
        position++
      }
      in '0'..'9' -> skipString()
      else -> throw IllegalArgumentException("Invalid bencoded value")
    }
  }

  private fun readString(maxDecodedLength: Int): String? {
    val length = readLength()
    val start = position
    position = checkedEnd(start, length)
    if (length > maxDecodedLength) return null
    return String(data, start, length, Charsets.UTF_8)
  }

  private fun skipString() {
    val length = readLength()
    position = checkedEnd(position, length)
  }

  private fun readLength(): Int {
    var length = 0L
    var digits = 0
    while (peek() in '0'..'9') {
      length = length * 10L + (data[position].toInt() - '0'.code)
      if (length > data.size) throw IllegalArgumentException("Bencoded string is too large")
      position++
      digits++
    }
    if (digits == 0) throw IllegalArgumentException("Missing bencoded string length")
    expect(':')
    return length.toInt()
  }

  private fun checkedEnd(
    start: Int,
    length: Int,
  ): Int {
    if (length < 0 || start < 0 || length > data.size - start) {
      throw IllegalArgumentException("Truncated bencoded string")
    }
    return start + length
  }

  private fun expect(character: Char) {
    if (peek() != character) throw IllegalArgumentException("Invalid torrent metadata")
    position++
  }

  private fun peek(): Char {
    if (position !in data.indices) throw IllegalArgumentException("Truncated torrent metadata")
    return data[position].toInt().toChar()
  }

  private fun checkDepth(depth: Int) {
    if (depth > MAX_DEPTH) throw IllegalArgumentException("Torrent metadata is nested too deeply")
  }
}
