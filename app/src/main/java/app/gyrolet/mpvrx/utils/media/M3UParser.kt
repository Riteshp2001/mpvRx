/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

data class M3UPlaylistItem(
  val url: String,
  val title: String? = null,
  val duration: Int = -1,
  val tvgId: String? = null,
  val tvgName: String? = null,
  val tvgLogo: String? = null,
  val groupTitle: String? = null,
  val licenseType: String? = null,
  val licenseKey: String? = null,
  val userAgent: String? = null,
)

sealed class M3UParseResult {
  data class Success(
    val playlistName: String,
    val items: List<M3UPlaylistItem>,
  ) : M3UParseResult()

  data class Error(
    val message: String,
    val exception: Throwable? = null,
  ) : M3UParseResult()
}

/** Resource limits shared by every M3U entry point. */
data class M3ULimits(
  val maxBytes: Long = 32L * 1024 * 1024,
  val maxLines: Int = 300_000,
  val maxEntries: Int = 100_000,
) {
  init {
    require(maxBytes > 0) { "maxBytes must be positive" }
    require(maxLines > 0) { "maxLines must be positive" }
    require(maxEntries > 0) { "maxEntries must be positive" }
  }
}

/** Bounded parser/loader for simple and extended M3U playlists. */
object M3UParser {
  private const val TIMEOUT_MS = 30_000L
  private const val DEFAULT_USER_AGENT = "mpvRx/2.0"
  private const val EXTINF_PREFIX = "#EXTINF:"
  private const val KODIPROP_PREFIX = "#KODIPROP:"
  private const val EXTVLCOPT_PREFIX = "#EXTVLCOPT:"
  private const val KODI_LICENSE_TYPE = "inputstream.adaptive.license_type"
  private const val KODI_LICENSE_KEY = "inputstream.adaptive.license_key"
  private const val HLS_ERROR = "HLS media manifest must be played directly"
  private const val BOM = '\uFEFF'

  private val defaultLimits = M3ULimits()
  private val extinfAttributeRegex =
    Regex("""([A-Za-z0-9_.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")
  private val defaultHttpClient by lazy {
    OkHttpClient
      .Builder()
      .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build()
  }

  /** Loads an HTTP(S) playlist without retaining credentials from URL user-info. */
  suspend fun parseFromUrl(
    url: String,
    userAgent: String? = null,
    headers: Map<String, String> = emptyMap(),
    httpClient: OkHttpClient = defaultHttpClient,
  ): M3UParseResult = parseFromUrl(url, userAgent, headers, httpClient, defaultLimits)

  suspend fun parseFromUrl(
    url: String,
    userAgent: String?,
    limits: M3ULimits,
  ): M3UParseResult = parseFromUrl(url, userAgent, emptyMap(), defaultHttpClient, limits)

  suspend fun parseFromUrl(
    url: String,
    userAgent: String?,
    headers: Map<String, String>,
    httpClient: OkHttpClient,
    limits: M3ULimits,
  ): M3UParseResult {
    val originalUrl = url.toHttpUrlOrNull() ?: return error("Invalid playlist URL")
    val username = originalUrl.username
    val password = originalUrl.password
    val safeUrl = originalUrl.newBuilder().username("").password("").build()
    val request =
      runCatching {
        val builder = Request.Builder().url(safeUrl)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
          builder.header("User-Agent", userAgent?.takeIf(String::isNotBlank) ?: DEFAULT_USER_AGENT)
        }
        if ((username.isNotEmpty() || password.isNotEmpty()) &&
          headers.keys.none { it.equals("Authorization", ignoreCase = true) }
        ) {
          builder.header("Authorization", Credentials.basic(username, password))
        }
        builder.build()
      }.getOrElse { return error("Invalid playlist request") }

    return try {
      httpClient.newCall(request).await().use { response ->
        if (!response.isSuccessful) return error("HTTP error: ${response.code}")
        if (response.body.contentLength() > limits.maxBytes) return error(byteLimitMessage(limits))
        parseFromStream(
          inputStream = response.body.byteStream(),
          sourceUrl = response.request.url.newBuilder().username("").password("").build().toString(),
          limits = limits,
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      error("Failed to load playlist")
    }
  }

  /** Loads a local/content playlist through the same bounded streaming path. */
  suspend fun parseFromUri(
    context: Context,
    uri: Uri,
  ): M3UParseResult =
    withContext(Dispatchers.IO) {
      try {
        // Do not call the player fd resolver here: its fallback detaches an fd for libmpv, while
        // this parser opens its own stream and would otherwise leak the detached descriptor.
        val sourceUrl = uri.toString()
        val rawFilename =
          context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
          } ?: uri.lastPathSegment
            ?: "Local M3U Playlist"
        val playlistName = cleanPlaylistName(decode(rawFilename), "Local M3U Playlist")
        val stream = context.contentResolver.openInputStream(uri) ?: return@withContext error("Failed to open file")

        parseFromStream(stream, sourceUrl, playlistName)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        error("Failed to parse playlist")
      }
    }

  /**
   * Parses and closes [inputStream]. Cancellation closes the stream so a blocked network read can unwind.
   */
  suspend fun parseFromStream(
    inputStream: InputStream,
    sourceUrl: String? = null,
    overridePlaylistName: String? = null,
    limits: M3ULimits = defaultLimits,
  ): M3UParseResult {
    val context = currentCoroutineContext()
    return try {
      runInterruptible(Dispatchers.IO) {
        inputStream.use { stream ->
          LimitedInputStream(stream, limits.maxBytes)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { reader -> parseReader(reader, sourceUrl, overridePlaylistName, limits) { context.ensureActive() } }
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: M3ULimitException) {
      context.ensureActive()
      error(error.message ?: "Playlist exceeds a safety limit")
    } catch (_: Exception) {
      context.ensureActive()
      error("Failed to read playlist")
    }
  }

  /** Compatibility wrapper for callers that already hold text. New loaders should use [parseFromStream]. */
  fun parseContent(
    content: String,
    sourceUrl: String? = null,
    overridePlaylistName: String? = null,
  ): M3UParseResult = parseContent(content, sourceUrl, overridePlaylistName, defaultLimits)

  fun parseContent(
    content: String,
    sourceUrl: String?,
    overridePlaylistName: String?,
    limits: M3ULimits,
  ): M3UParseResult =
    try {
      if (content.toByteArray(StandardCharsets.UTF_8).size.toLong() > limits.maxBytes) {
        return error(byteLimitMessage(limits))
      }
      BufferedReader(StringReader(content)).use { reader ->
        parseReader(reader, sourceUrl, overridePlaylistName, limits)
      }
    } catch (error: M3ULimitException) {
      error(error.message ?: "Playlist exceeds a safety limit")
    } catch (_: Exception) {
      error("Failed to parse playlist content")
    }

  fun isLikelyHlsMediaManifest(content: String): Boolean =
    content.lineSequence().any { rawLine -> normalizeLine(rawLine).startsWith("#EXT-X-", ignoreCase = true) }

  fun shouldPlayHlsDirectly(result: M3UParseResult): Boolean =
    result is M3UParseResult.Error && result.message == HLS_ERROR

  /** Removes `user:password@` from a source before it is logged, persisted, or used as an entry base. */
  fun sanitizeSourceUrl(sourceUrl: String): String = stripUriUserInfo(sourceUrl)

  private fun parseReader(
    reader: BufferedReader,
    sourceUrl: String?,
    overridePlaylistName: String?,
    limits: M3ULimits,
    checkCancellation: () -> Unit = {},
  ): M3UParseResult {
    val items = ArrayList<M3UPlaylistItem>(minOf(limits.maxEntries, 1_024))
    val pending = PendingEntry()
    var lineCount = 0
    var foundContent = false

    while (true) {
      checkCancellation()
      val rawLine = reader.readLine() ?: break
      lineCount++
      if (lineCount > limits.maxLines) throw M3ULimitException("Playlist exceeds ${limits.maxLines} lines")

      val line = normalizeLine(rawLine)
      if (line.isEmpty()) continue
      foundContent = true

      when {
        line.startsWith("#EXT-X-", ignoreCase = true) -> return error(HLS_ERROR)
        line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
        line.startsWith(EXTINF_PREFIX, ignoreCase = true) -> {
          pending.clearExtInf()
          parseExtInf(line.substring(EXTINF_PREFIX.length).trim(), pending)
        }
        line.startsWith(KODIPROP_PREFIX, ignoreCase = true) -> {
          parseKodiProperty(line.substring(KODIPROP_PREFIX.length).trim(), pending)
        }
        line.startsWith(EXTVLCOPT_PREFIX, ignoreCase = true) -> {
          parseVlcOption(line.substring(EXTVLCOPT_PREFIX.length).trim(), pending)
        }
        line.startsWith('#') -> Unit
        else -> {
          if (items.size >= limits.maxEntries) {
            throw M3ULimitException("Playlist exceeds ${limits.maxEntries} entries")
          }
          val mediaUrl = resolveMediaUrl(sourceUrl, line)
          if (mediaUrl.isNotEmpty()) {
            items +=
              M3UPlaylistItem(
                url = mediaUrl,
                title = pending.title ?: pending.tvgName ?: extractTitleFromUrl(mediaUrl),
                duration = pending.duration,
                tvgId = pending.tvgId,
                tvgName = pending.tvgName,
                tvgLogo = pending.tvgLogo?.let(::stripUriUserInfo),
                groupTitle = pending.groupTitle,
                licenseType = pending.licenseType,
                licenseKey = pending.licenseKey?.let(::stripUriUserInfo),
                userAgent = pending.userAgent,
              )
          }
          pending.clear()
        }
      }
    }

    if (!foundContent) return error("Playlist is empty")
    if (items.isEmpty()) return error("No valid media URLs found in playlist")
    val playlistName =
      overridePlaylistName?.takeIf(String::isNotBlank)
        ?: sourceUrl?.let(::extractPlaylistName)
        ?: "M3U Playlist"
    return M3UParseResult.Success(playlistName, items)
  }

  private fun parseExtInf(
    info: String,
    pending: PendingEntry,
  ) {
    val commaIndex = firstUnquotedComma(info)
    val descriptor = if (commaIndex >= 0) info.substring(0, commaIndex).trim() else info.trim()
    pending.title =
      if (commaIndex >= 0) info.substring(commaIndex + 1).trim().ifBlank { null } else null

    val durationEnd = descriptor.indexOfFirst(Char::isWhitespace).let { if (it < 0) descriptor.length else it }
    val duration = descriptor.substring(0, durationEnd).toDoubleOrNull()
    pending.duration = duration?.toInt() ?: -1
    val metadata = if (duration == null) descriptor else descriptor.substring(durationEnd).trim()
    extinfAttributeRegex.findAll(metadata).forEach { match ->
      val key = match.groupValues[1].lowercase()
      val value = match.groupValues.drop(2).firstOrNull(String::isNotEmpty) ?: return@forEach
      when (key) {
        "tvg-id" -> pending.tvgId = value
        "tvg-name" -> pending.tvgName = value
        "tvg-logo" -> pending.tvgLogo = value
        "group-title" -> pending.groupTitle = value
      }
    }
  }

  private fun parseKodiProperty(
    property: String,
    pending: PendingEntry,
  ) {
    val separator = property.indexOf('=')
    if (separator <= 0) return
    val key = property.substring(0, separator).trim().lowercase()
    val value = property.substring(separator + 1).trim().ifBlank { null } ?: return
    when (key) {
      KODI_LICENSE_TYPE -> pending.licenseType = value
      KODI_LICENSE_KEY -> pending.licenseKey = value
    }
  }

  private fun parseVlcOption(
    option: String,
    pending: PendingEntry,
  ) {
    val separator = option.indexOf('=')
    if (separator <= 0) return
    val key = option.substring(0, separator).trim().lowercase()
    val value = option.substring(separator + 1).trim().ifBlank { null } ?: return
    if (key == "http-user-agent") pending.userAgent = value
  }

  private fun resolveMediaUrl(
    sourceUrl: String?,
    entry: String,
  ): String {
    val optionStart = entry.indexOf('|')
    val resource = (if (optionStart >= 0) entry.substring(0, optionStart) else entry).trim()
    val optionSuffix = if (optionStart >= 0) entry.substring(optionStart) else ""
    if (resource.isEmpty()) return ""
    val parsedEntry = parseUriLeniently(resource)

    if (parsedEntry?.isAbsolute == true) return stripUriUserInfo(resource) + optionSuffix
    val source = sourceUrl ?: return stripUriUserInfo(resource) + optionSuffix
    val parsedSource = parseUriLeniently(source)

    if (parsedSource?.scheme.equals("http", true) || parsedSource?.scheme.equals("https", true)) {
      val resolved =
        stripUserInfo(parsedSource!!)
          .toString()
          .toHttpUrlOrNull()
          ?.resolve(".")
          ?.resolve(resource)
      if (resolved != null) return resolved.newBuilder().username("").password("").build().toString() + optionSuffix
    }

    if (parsedSource != null && (parsedSource.isAbsolute || parsedSource.rawAuthority != null)) {
      val safeBase = stripUserInfo(parsedSource).withoutQueryOrFragment().resolve(".")
      val resolved = runCatching { safeBase.resolve(parsedEntry ?: URI(resource)).normalize() }.getOrNull()
      if (resolved != null) return stripUserInfo(resolved).toString() + optionSuffix
    }

    val parent = File(source).parentFile ?: return stripUriUserInfo(resource) + optionSuffix
    return File(parent, resource).normalize().path + optionSuffix
  }

  private fun stripUriUserInfo(value: String): String {
    val resource = value.substringBefore('|')
    val suffix = value.substring(resource.length)
    val uri = parseUriLeniently(resource) ?: return stripUserInfoFallback(resource) + suffix
    return stripUserInfo(uri).toString() + suffix
  }

  private fun stripUserInfoFallback(value: String): String {
    val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return value
    val authorityEnd =
      value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: value.length
    val at = value.lastIndexOf('@', authorityEnd - 1)
    return if (at >= authorityStart) value.removeRange(authorityStart, at + 1) else value
  }

  private fun stripUserInfo(uri: URI): URI {
    val authority = uri.rawAuthority ?: return uri
    if (uri.rawUserInfo == null) return uri
    val safeAuthority = authority.substringAfterLast('@')
    val rebuilt = buildString {
      uri.scheme?.let { append(it).append(':') }
      append("//").append(safeAuthority)
      append(uri.rawPath.orEmpty())
      uri.rawQuery?.let { append('?').append(it) }
      uri.rawFragment?.let { append('#').append(it) }
    }
    return URI(rebuilt)
  }

  private fun URI.withoutQueryOrFragment(): URI {
    if (rawQuery == null && rawFragment == null) return this
    val value = toString()
    val end = listOf(value.indexOf('?'), value.indexOf('#')).filter { it >= 0 }.minOrNull() ?: value.length
    return URI(value.substring(0, end))
  }

  private fun parseUriLeniently(value: String): URI? =
    runCatching { URI(value) }.getOrElse {
      runCatching { URI(value.replace(" ", "%20")) }.getOrNull()
    }

  private fun extractTitleFromUrl(url: String): String {
    val resource = url.substringBefore('|')
    val path = parseUriLeniently(resource)?.path ?: resource
    val filename = path.substringAfterLast('/')
    return decode(filename.substringBeforeLast('.'))
      .replace('_', ' ')
      .replace('-', ' ')
      .trim()
      .ifBlank { filename.take(60) }
  }

  private fun extractPlaylistName(sourceUrl: String): String {
    val safeSource = stripUriUserInfo(sourceUrl)
    val sourceUri = parseUriLeniently(safeSource)
    val path = sourceUri?.path ?: safeSource.substringBefore('?').substringBefore('#')
    val filename = path.substringAfterLast('/')
    val name = cleanPlaylistName(decode(filename), "M3U Playlist")
    return if (sourceUri?.scheme.equals("http", true) || sourceUri?.scheme.equals("https", true)) {
      name.replaceFirstChar { it.uppercase() }
    } else {
      name
    }
  }

  private fun cleanPlaylistName(
    filename: String,
    fallback: String,
  ): String =
    filename
      .substringBeforeLast('.')
      .replace('_', ' ')
      .replace('-', ' ')
      .trim()
      .ifBlank { fallback }

  private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

  private fun normalizeLine(line: String): String = line.trim().removePrefix(BOM.toString()).trimStart()

  private fun firstUnquotedComma(value: String): Int {
    var quote: Char? = null
    value.forEachIndexed { index, char ->
      when {
        quote == null && (char == '"' || char == '\'') -> quote = char
        quote == char -> quote = null
        quote == null && char == ',' -> return index
      }
    }
    return -1
  }

  private fun error(message: String): M3UParseResult.Error = M3UParseResult.Error(message)

  private fun byteLimitMessage(limits: M3ULimits): String = "Playlist exceeds ${limits.maxBytes} bytes"

  private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { cancel() }
      enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            // The resource-aware resume overload closes the response if cancellation wins either
            // before dispatch or while the resumed coroutine is waiting to run.
            continuation.resume(response) { _, rejectedResponse, _ -> rejectedResponse.close() }
          }
        },
      )
    }

  private class PendingEntry {
    var title: String? = null
    var duration: Int = -1
    var tvgId: String? = null
    var tvgName: String? = null
    var tvgLogo: String? = null
    var groupTitle: String? = null
    var licenseType: String? = null
    var licenseKey: String? = null
    var userAgent: String? = null

    fun clearExtInf() {
      title = null
      duration = -1
      tvgId = null
      tvgName = null
      tvgLogo = null
      groupTitle = null
    }

    fun clear() {
      clearExtInf()
      licenseType = null
      licenseKey = null
      userAgent = null
    }
  }

  private class M3ULimitException(
    message: String,
  ) : IOException(message)

  private class LimitedInputStream(
    input: InputStream,
    private val maxBytes: Long,
  ) : FilterInputStream(input) {
    private var byteCount = 0L

    override fun read(): Int {
      if (byteCount >= maxBytes) return readPastLimit()
      return super.read().also { if (it >= 0) byteCount++ }
    }

    override fun read(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (length == 0) return 0
      val remaining = maxBytes - byteCount
      if (remaining <= 0) return readPastLimit()
      return super.read(buffer, offset, minOf(length.toLong(), remaining).toInt()).also { read ->
        if (read > 0) byteCount += read
      }
    }

    private fun readPastLimit(): Int {
      if (super.read() == -1) return -1
      throw M3ULimitException("Playlist exceeds $maxBytes bytes")
    }
  }
}
