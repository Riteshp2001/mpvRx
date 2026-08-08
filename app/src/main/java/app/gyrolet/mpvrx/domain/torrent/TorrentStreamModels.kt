/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.torrent

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class TorrentStreamRequest(
  val source: String,
  val fileIndex: Int? = null,
)

data class TorrentFileItem(
  val index: Int,
  val path: String,
  val name: String,
  val size: Long,
  val mimeType: String,
)

data class TorrentStreamResult(
  val localUrl: String,
  val selectedFile: TorrentFileItem,
  /** A durable source. This is never the temporary loopback playback URL. */
  val source: String,
  val infoHash: String,
  val torrentName: String,
  val playableFiles: List<TorrentFileItem>,
)

data class ParsedMagnet(
  val infoHash: String,
  val cleanMagnetUri: String,
  val fileIdx: Int?,
  val displayName: String?,
  val trackers: List<String>,
)

sealed class TorrentStreamingState {
  data object Idle : TorrentStreamingState()

  data class Connecting(
    val phase: String = "Starting Torrent Engine...",
    val downloadSpeed: Long = 0L,
    val uploadSpeed: Long = 0L,
    val peers: Int = 0,
    val seeds: Int = 0,
  ) : TorrentStreamingState()

  data class Streaming(
    val localUrl: String,
    val selectedFileIndex: Int,
    val fileName: String,
    val fileSize: Long,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peers: Int,
    val seeds: Int,
    val bufferProgress: Float,
    val totalProgress: Float,
    val downloadedBytes: Long = 0L,
  ) : TorrentStreamingState()

  data class Error(val message: String) : TorrentStreamingState()
}

class TorrentStreamException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

val DEFAULT_TORRENT_TRACKERS =
  listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://p4p.arenabg.com:1337/announce",
    "https://tracker.opentrackr.org:443/announce",
  )

private val v1HexHash = Regex("^[0-9a-fA-F]{40}$")
private val v2HexHash = Regex("^[0-9a-fA-F]{64}$")
private val v1Base32Hash = Regex("^[A-Z2-7a-z2-7]{32}$")
private val torrentMimeTypes =
  setOf(
    "application/x-bittorrent",
    "application/x-torrent",
    "application/torrent",
  )

/** Pure source classifier used before Android turns an unrecognised string into a file URI. */
fun isTorrentSource(
  source: String,
  mimeType: String? = null,
): Boolean {
  val value = source.trim()
  if (value.isEmpty()) return false
  val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()
  if (normalizedMimeType != null && normalizedMimeType in torrentMimeTypes) return true
  if (v1HexHash.matches(value) || v1Base32Hash.matches(value) || v2HexHash.matches(value)) return true
  if (value.startsWith("magnet:?", ignoreCase = true)) {
    return magnetParameters(value).any { (key, item) ->
      key.equals("xt", ignoreCase = true) &&
        (item.startsWith("urn:btih:", ignoreCase = true) || item.startsWith("urn:btmh:", ignoreCase = true))
    }
  }
  if (value.startsWith("torrent:", ignoreCase = true)) return true

  val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
  return scheme in setOf("content", "file", "http", "https") &&
    value.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true)
}

/**
 * Returns a source the torrent engine can consume, or null when [source] is not a torrent source.
 * Magnet parameters are preserved; raw and torrent:// v1 hashes are converted to magnets.
 */
fun normalizeTorrentSource(source: String): String? {
  val value = source.trim()
  if (value.isEmpty()) return null

  canonicalV1Hash(value)?.let { return buildMagnetUri(it) }
  if (v2HexHash.matches(value)) return "torrent://${value.lowercase()}"

  if (value.startsWith("torrent:", ignoreCase = true)) {
    val payload =
      value.substringAfter(':')
        .removePrefix("//")
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
    canonicalV1Hash(payload)?.let { return buildMagnetUri(it) }
    return value.takeIf { v2HexHash.matches(payload) }
  }

  if (value.startsWith("magnet:?", ignoreCase = true) && isTorrentSource(value)) {
    return value
  }

  val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
  if (scheme in setOf("content", "file", "http", "https") &&
    value.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true)
  ) {
    return value
  }
  return null
}

fun canonicalInfoHash(raw: String): String? {
  val value = raw.trim()
  canonicalV1Hash(value)?.let { return it }

  if (value.startsWith("torrent:", ignoreCase = true)) {
    val payload =
      value.substringAfter(':')
        .removePrefix("//")
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
    return canonicalV1Hash(payload)
  }

  if (!value.startsWith("magnet:?", ignoreCase = true)) return null
  val topic =
    magnetParameters(value)
      .firstOrNull { (key, item) -> key.equals("xt", true) && item.startsWith("urn:btih:", true) }
      ?.second
      ?.substringAfterLast(':')
      ?: return null
  return canonicalV1Hash(topic)
}

fun parseMagnet(raw: String): ParsedMagnet? {
  val normalized = normalizeTorrentSource(raw) ?: return null
  val hash = canonicalInfoHash(normalized) ?: return null
  val parameters = magnetParameters(normalized)
  val fileIndex =
    parameters.firstNotNullOfOrNull { (key, value) ->
      if (key.equals("so", true) || key.equals("index", true) || key.equals("fileIndex", true)) {
        value.substringBefore(',').toIntOrNull()?.takeIf { it >= 0 }
      } else {
        null
      }
    }
  val name = parameters.firstOrNull { it.first.equals("dn", true) }?.second?.takeIf(String::isNotBlank)
  val trackers = parameters.filter { it.first.equals("tr", true) }.map { it.second }.filter(String::isNotBlank).distinct()
  return ParsedMagnet(
    infoHash = hash,
    cleanMagnetUri = normalized,
    fileIdx = fileIndex,
    displayName = name,
    trackers = trackers,
  )
}

fun buildMagnetUri(
  infoHash: String,
  trackers: List<String> = DEFAULT_TORRENT_TRACKERS,
  displayName: String? = null,
  webSeeds: List<String> = emptyList(),
): String {
  val hash = canonicalV1Hash(infoHash) ?: return ""
  return buildString {
    append("magnet:?xt=urn:btih:")
    append(hash)
    displayName?.takeIf(String::isNotBlank)?.let {
      append("&dn=")
      append(encodeUriComponent(it))
    }
    trackers.filter(String::isNotBlank).distinct().forEach {
      append("&tr=")
      append(encodeUriComponent(it))
    }
    webSeeds.filter(String::isNotBlank).distinct().forEach {
      append("&ws=")
      append(encodeUriComponent(it))
    }
  }
}

/** Removes per-file selectors while retaining standard magnet extensions such as ws/xs/as. */
internal fun magnetWithoutFileSelection(source: String): String {
  if (!source.startsWith("magnet:?", ignoreCase = true)) return source
  val kept =
    source.substringAfter('?', "")
      .split('&')
      .filter { part ->
        val key = part.substringBefore('=').lowercase()
        key !in setOf("so", "index", "fileindex", "file_index", "fileidx", "indices")
      }
  return "magnet:?${kept.joinToString("&")}"
}

internal fun hasV2OnlyMagnet(source: String): Boolean {
  if (v2HexHash.matches(source.trim())) return true
  if (source.startsWith("torrent:", true)) {
    val payload = source.substringAfter(':').removePrefix("//").substringBefore('?').substringBefore('#').trim('/')
    return v2HexHash.matches(payload)
  }
  if (!source.startsWith("magnet:?", true)) return false
  val topics = magnetParameters(source).filter { it.first.equals("xt", true) }.map { it.second }
  return topics.any { it.startsWith("urn:btmh:", true) } && topics.none { it.startsWith("urn:btih:", true) }
}

private fun canonicalV1Hash(value: String): String? =
  when {
    v1HexHash.matches(value) -> value.lowercase()
    v1Base32Hash.matches(value) -> base32ToHex(value)
    else -> null
  }

private fun magnetParameters(source: String): List<Pair<String, String>> =
  source.substringAfter('?', "")
    .split('&')
    .mapNotNull { part ->
      val separator = part.indexOf('=')
      if (separator <= 0) return@mapNotNull null
      val key = decodeUriComponent(part.substring(0, separator))
      val value = decodeUriComponent(part.substring(separator + 1))
      key to value
    }

private fun decodeUriComponent(value: String): String =
  runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

private fun base32ToHex(value: String): String? {
  val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
  val output = ByteArray(20)
  var buffer = 0
  var bits = 0
  var outputIndex = 0
  for (character in value.uppercase()) {
    val digit = alphabet.indexOf(character)
    if (digit < 0) return null
    buffer = (buffer shl 5) or digit
    bits += 5
    if (bits >= 8) {
      bits -= 8
      if (outputIndex >= output.size) return null
      output[outputIndex++] = ((buffer shr bits) and 0xff).toByte()
    }
  }
  if (outputIndex != output.size) return null
  return output.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun encodeUriComponent(value: String): String =
  buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
      val item = byte.toInt() and 0xff
      if (
        item in 'a'.code..'z'.code || item in 'A'.code..'Z'.code || item in '0'.code..'9'.code ||
        item == '-'.code || item == '.'.code || item == '_'.code || item == '~'.code
      ) {
        append(item.toChar())
      } else {
        append('%')
        append(item.toString(16).padStart(2, '0').uppercase())
      }
    }
  }

fun formatTorrentSpeed(bytesPerSec: Long): String =
  when {
    bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
    else -> "$bytesPerSec B/s"
  }

fun formatTorrentBytes(bytes: Long): String =
  when {
    bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format("%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
  }
