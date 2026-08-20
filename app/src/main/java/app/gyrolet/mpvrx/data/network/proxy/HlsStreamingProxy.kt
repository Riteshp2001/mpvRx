/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.proxy

import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * An embedded loopback proxy for HLS (m3u8) streams.
 *
 * Resolves and normalizes complex, multi-rendition, split-audio/video, and header-authenticated
 * HLS manifests using OkHttp on Android, rewriting manifests to route all child playlists,
 * media segments, and encryption keys through local loopback (127.0.0.1) for libmpv.
 */
class HlsStreamingProxy private constructor() :
  NanoHTTPD("127.0.0.1", 0) {

  companion object {
    private const val TAG = "HlsStreamingProxy"
    private const val TOKEN_BYTES = 24
    private const val TIMEOUT_SECONDS = 30L
    private const val MIME_M3U8 = "application/vnd.apple.mpegurl"
    private const val MIME_OCTET = "application/octet-stream"

    @Volatile
    private var instance: HlsStreamingProxy? = null

    fun getInstance(): HlsStreamingProxy =
      instance ?: synchronized(this) {
        instance ?: HlsStreamingProxy().also {
          it.start(SOCKET_READ_TIMEOUT, false)
          instance = it
        }
      }

    fun stopInstance() {
      synchronized(this) {
        instance?.let { proxy ->
          proxy.stop()
          instance = null
        }
      }
    }
  }

  data class HlsSession(
    val sourceUrl: String,
    val headers: Map<String, String>,
    val userAgent: String?,
  )

  private val random = SecureRandom()
  private val proxyJob = SupervisorJob()
  private val proxyScope = CoroutineScope(Dispatchers.IO + proxyJob)
  private val tokenByRegistration = ConcurrentHashMap<String, String>()
  private val sessionsByToken = ConcurrentHashMap<String, HlsSession>()

  private val httpClient: OkHttpClient by lazy {
    SharedHttpClient.derive {
      connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      followRedirects(true)
      followSslRedirects(true)
    }
  }

  /**
   * Registers a remote HLS stream URL and returns a local loopback master playlist URL.
   */
  @Synchronized
  fun registerStream(
    streamId: String,
    sourceUrl: String,
    headers: Map<String, String> = emptyMap(),
    userAgent: String? = null,
  ): String {
    val token = generateToken()
    val session = HlsSession(
      sourceUrl = sourceUrl,
      headers = headers,
      userAgent = userAgent,
    )
    sessionsByToken[token] = session
    tokenByRegistration.put(streamId, token)?.let { oldToken ->
      sessionsByToken.remove(oldToken)
    }

    val route = "/hls/$token/master.m3u8"
    return URI("http", null, "127.0.0.1", listeningPort, route, null, null).toASCIIString()
  }

  /**
   * Unregisters the stream and releases associated session resources.
   */
  @Synchronized
  fun unregisterStream(streamId: String) {
    val token = tokenByRegistration.remove(streamId) ?: return
    sessionsByToken.remove(token)
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri
    val method = session.method
    val headOnly = method == Method.HEAD

    if (method != Method.GET && method != Method.HEAD) {
      return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        .apply { addHeader("Allow", "GET, HEAD") }
    }

    // Path pattern: /hls/{token}/{type}
    if (!uri.startsWith("/hls/")) {
      return notFound(headOnly)
    }

    val pathParts = uri.removePrefix("/hls/").split("/", limit = 2)
    if (pathParts.isEmpty()) return notFound(headOnly)
    val token = pathParts[0]
    val subPath = pathParts.getOrNull(1).orEmpty()

    val hlsSession = sessionsByToken[token] ?: return notFound(headOnly)

    return try {
      when {
        subPath.equals("master.m3u8", ignoreCase = true) -> {
          handleMasterManifest(token, hlsSession, headOnly)
        }
        subPath.startsWith("variant.m3u8", ignoreCase = true) -> {
          val targetUrl = session.parameters["u"]?.firstOrNull()?.let(::decodeUrl)
          if (targetUrl.isNullOrBlank()) return notFound(headOnly)
          handleVariantManifest(token, hlsSession, targetUrl, headOnly)
        }
        subPath.startsWith("segment", ignoreCase = true) -> {
          val targetUrl = session.parameters["u"]?.firstOrNull()?.let(::decodeUrl)
          if (targetUrl.isNullOrBlank()) return notFound(headOnly)
          val rangeHeader = session.headers["range"]
          handleSegment(hlsSession, targetUrl, rangeHeader, headOnly)
        }
        subPath.startsWith("key", ignoreCase = true) -> {
          val targetUrl = session.parameters["u"]?.firstOrNull()?.let(::decodeUrl)
          if (targetUrl.isNullOrBlank()) return notFound(headOnly)
          handleKey(hlsSession, targetUrl, headOnly)
        }
        else -> notFound(headOnly)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error handling HLS proxy request for $uri: ${e.message}")
      newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "HLS Proxy Error: ${e.message}")
        .apply { addCorsHeaders(this) }
    }
  }

  private class CustomStatus(private val code: Int, private val desc: String) : Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $desc"
  }

  private fun handleMasterManifest(
    token: String,
    session: HlsSession,
    headOnly: Boolean,
  ): Response {
    val requestBuilder = Request.Builder().url(session.sourceUrl)
    applyHeaders(requestBuilder, session.headers, session.userAgent, session.sourceUrl)

    val okResponse: OkResponse
    try {
      okResponse = httpClient.newCall(requestBuilder.build()).execute()
    } catch (e: IOException) {
      Log.e(TAG, "Failed to fetch master manifest from upstream: ${e.message}")
      return newFixedLengthResponse(CustomStatus(502, "Bad Gateway"), MIME_PLAINTEXT, "Failed to fetch upstream manifest")
        .apply { addCorsHeaders(this) }
    }

    if (!okResponse.isSuccessful) {
      val code = okResponse.code
      okResponse.close()
      return newFixedLengthResponse(
        Response.Status.lookup(code) ?: CustomStatus(code, "Upstream Error"),
        MIME_PLAINTEXT,
        "Upstream returned HTTP $code",
      ).apply { addCorsHeaders(this) }
    }

    val content = okResponse.body.string()
    val finalUrl = okResponse.request.url.toString()
    okResponse.close()

    val rewritten = rewriteMasterManifest(content, token, finalUrl)
    val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
    val stream = if (headOnly) ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(bytes)

    return newFixedLengthResponse(Response.Status.OK, MIME_M3U8, stream, bytes.size.toLong()).apply {
      addCorsHeaders(this)
      addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
    }
  }

  private fun handleVariantManifest(
    token: String,
    session: HlsSession,
    variantUrl: String,
    headOnly: Boolean,
  ): Response {
    val requestBuilder = Request.Builder().url(variantUrl)
    applyHeaders(requestBuilder, session.headers, session.userAgent, session.sourceUrl)

    val okResponse: OkResponse
    try {
      okResponse = httpClient.newCall(requestBuilder.build()).execute()
    } catch (e: IOException) {
      Log.e(TAG, "Failed to fetch variant manifest from upstream ($variantUrl): ${e.message}")
      return newFixedLengthResponse(CustomStatus(502, "Bad Gateway"), MIME_PLAINTEXT, "Failed to fetch upstream variant")
        .apply { addCorsHeaders(this) }
    }

    if (!okResponse.isSuccessful) {
      val code = okResponse.code
      okResponse.close()
      return newFixedLengthResponse(
        Response.Status.lookup(code) ?: CustomStatus(code, "Upstream Error"),
        MIME_PLAINTEXT,
        "Upstream returned HTTP $code",
      ).apply { addCorsHeaders(this) }
    }

    val content = okResponse.body.string()
    val finalUrl = okResponse.request.url.toString()
    okResponse.close()

    val rewritten = rewriteVariantManifest(content, token, finalUrl)
    val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
    val stream = if (headOnly) ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(bytes)

    return newFixedLengthResponse(Response.Status.OK, MIME_M3U8, stream, bytes.size.toLong()).apply {
      addCorsHeaders(this)
      addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
    }
  }

  private fun handleSegment(
    session: HlsSession,
    segmentUrl: String,
    rangeHeader: String?,
    headOnly: Boolean,
  ): Response {
    val requestBuilder = Request.Builder().url(segmentUrl)
    applyHeaders(requestBuilder, session.headers, session.userAgent, session.sourceUrl)
    if (!rangeHeader.isNullOrBlank()) {
      requestBuilder.header("Range", rangeHeader)
    }

    val okResponse: OkResponse
    try {
      okResponse = httpClient.newCall(requestBuilder.build()).execute()
    } catch (e: IOException) {
      Log.e(TAG, "Failed to fetch segment ($segmentUrl): ${e.message}")
      return newFixedLengthResponse(CustomStatus(502, "Bad Gateway"), MIME_PLAINTEXT, "Failed to fetch segment")
        .apply { addCorsHeaders(this) }
    }

    val code = okResponse.code
    val isPartial = code == 206
    val status = if (isPartial) Response.Status.PARTIAL_CONTENT else if (okResponse.isSuccessful) Response.Status.OK else Response.Status.lookup(code) ?: CustomStatus(code, "Upstream Error")

    if (!okResponse.isSuccessful && !isPartial) {
      okResponse.close()
      return newFixedLengthResponse(status, MIME_PLAINTEXT, "Upstream returned HTTP $code")
        .apply { addCorsHeaders(this) }
    }

    val contentType = okResponse.header("Content-Type") ?: guessContentType(segmentUrl)
    val contentLength = okResponse.body.contentLength()
    val contentRange = okResponse.header("Content-Range")
    val bodyStream = if (headOnly) {
      okResponse.close()
      ByteArrayInputStream(ByteArray(0))
    } else {
      okResponse.body.byteStream()
    }

    val response = if (contentLength >= 0L) {
      newFixedLengthResponse(status, contentType, bodyStream, contentLength)
    } else {
      newChunkedResponse(status, contentType, bodyStream)
    }

    addCorsHeaders(response)
    response.addHeader("Accept-Ranges", "bytes")
    if (contentRange != null) {
      response.addHeader("Content-Range", contentRange)
    }

    return response
  }

  private fun handleKey(
    session: HlsSession,
    keyUrl: String,
    headOnly: Boolean,
  ): Response {
    val requestBuilder = Request.Builder().url(keyUrl)
    applyHeaders(requestBuilder, session.headers, session.userAgent, session.sourceUrl)

    val okResponse: OkResponse
    try {
      okResponse = httpClient.newCall(requestBuilder.build()).execute()
    } catch (e: IOException) {
      Log.e(TAG, "Failed to fetch key ($keyUrl): ${e.message}")
      return newFixedLengthResponse(CustomStatus(502, "Bad Gateway"), MIME_PLAINTEXT, "Failed to fetch key")
        .apply { addCorsHeaders(this) }
    }

    if (!okResponse.isSuccessful) {
      val code = okResponse.code
      okResponse.close()
      return newFixedLengthResponse(
        Response.Status.lookup(code) ?: CustomStatus(code, "Upstream Error"),
        MIME_PLAINTEXT,
        "Upstream key fetch returned HTTP $code",
      ).apply { addCorsHeaders(this) }
    }

    val bytes = okResponse.body.bytes()
    okResponse.close()

    val stream = if (headOnly) ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(bytes)
    return newFixedLengthResponse(Response.Status.OK, MIME_OCTET, stream, bytes.size.toLong()).apply {
      addCorsHeaders(this)
    }
  }

  private fun rewriteMasterManifest(
    manifestContent: String,
    token: String,
    baseUrl: String,
  ): String {
    val lines = manifestContent.lines()
    val rewrittenLines = mutableListOf<String>()
    var nextLineIsVariant = false

    val isMasterPlaylist = lines.any { it.trim().startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) }

    for (rawLine in lines) {
      val line = rawLine.trim()
      if (line.isEmpty()) {
        rewrittenLines.add(rawLine)
        continue
      }

      if (nextLineIsVariant) {
        if (!line.startsWith("#")) {
          val resolvedVariantUrl = resolveUrl(baseUrl, line)
          val proxiedVariantUrl = "/hls/$token/variant.m3u8?u=${encodeUrl(resolvedVariantUrl)}"
          rewrittenLines.add(proxiedVariantUrl)
          nextLineIsVariant = false
          continue
        }
      }

      when {
        line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
          rewrittenLines.add(line)
          nextLineIsVariant = true
        }
        line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
          val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)
          if (uriMatch != null) {
            val originalUri = uriMatch.groupValues[1]
            val resolvedUri = resolveUrl(baseUrl, originalUri)
            val proxiedUri = "/hls/$token/variant.m3u8?u=${encodeUrl(resolvedUri)}"
            val rewrittenMediaLine = line.replace(uriMatch.value, """URI="$proxiedUri"""")
            rewrittenLines.add(rewrittenMediaLine)
          } else {
            rewrittenLines.add(line)
          }
        }
        !isMasterPlaylist && !line.startsWith("#") -> {
          // Single-variant media playlist received as master
          val resolvedSegmentUrl = resolveUrl(baseUrl, line)
          val proxiedSegmentUrl = "/hls/$token/segment?u=${encodeUrl(resolvedSegmentUrl)}"
          rewrittenLines.add(proxiedSegmentUrl)
        }
        else -> {
          rewrittenLines.add(line)
        }
      }
    }

    return rewrittenLines.joinToString("\n")
  }

  private fun rewriteVariantManifest(
    manifestContent: String,
    token: String,
    baseUrl: String,
  ): String {
    val lines = manifestContent.lines()
    val rewrittenLines = mutableListOf<String>()

    for (rawLine in lines) {
      val line = rawLine.trim()
      if (line.isEmpty()) {
        rewrittenLines.add(rawLine)
        continue
      }

      when {
        line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
          val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)
          if (uriMatch != null) {
            val originalUri = uriMatch.groupValues[1]
            val resolvedUri = resolveUrl(baseUrl, originalUri)
            val proxiedUri = "/hls/$token/key?u=${encodeUrl(resolvedUri)}"
            rewrittenLines.add(line.replace(uriMatch.value, """URI="$proxiedUri""""))
          } else {
            rewrittenLines.add(line)
          }
        }
        line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
          val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)
          if (uriMatch != null) {
            val originalUri = uriMatch.groupValues[1]
            val resolvedUri = resolveUrl(baseUrl, originalUri)
            val proxiedUri = "/hls/$token/segment?u=${encodeUrl(resolvedUri)}"
            rewrittenLines.add(line.replace(uriMatch.value, """URI="$proxiedUri""""))
          } else {
            rewrittenLines.add(line)
          }
        }
        line.startsWith("#") -> {
          // Remove problematic double comments like "# #EXTINF" that break parsers
          if (line.startsWith("# #")) {
            rewrittenLines.add("#" + line.substring(3).trim())
          } else {
            rewrittenLines.add(line)
          }
        }
        else -> {
          // Segment URL
          val resolvedSegmentUrl = resolveUrl(baseUrl, line)
          val proxiedSegmentUrl = "/hls/$token/segment?u=${encodeUrl(resolvedSegmentUrl)}"
          rewrittenLines.add(proxiedSegmentUrl)
        }
      }
    }

    return rewrittenLines.joinToString("\n")
  }

  private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
    val cleanRelative = relativeUrl.trim()
    val base = baseUrl.toHttpUrlOrNull() ?: return cleanRelative
    val resolved = base.resolve(cleanRelative)
    return resolved?.toString() ?: cleanRelative
  }

  private fun applyHeaders(
    builder: Request.Builder,
    headers: Map<String, String>,
    userAgent: String?,
    fallbackReferer: String?,
  ) {
    var hasUserAgent = false
    var hasReferer = false

    headers.forEach { (key, value) ->
      if (key.equals("User-Agent", ignoreCase = true)) {
        hasUserAgent = true
        builder.header("User-Agent", value)
      } else if (key.equals("Referer", ignoreCase = true)) {
        hasReferer = true
        builder.header("Referer", value)
      } else {
        builder.header(key, value)
      }
    }

    if (!hasUserAgent && !userAgent.isNullOrBlank()) {
      builder.header("User-Agent", userAgent)
    } else if (!hasUserAgent) {
      builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    }

    if (!hasReferer && !fallbackReferer.isNullOrBlank()) {
      val refererUri = Uri.parse(fallbackReferer)
      val host = refererUri.host
      if (host != null) {
        builder.header("Referer", "${refererUri.scheme ?: "https"}://$host/")
      }
    }
  }

  private fun guessContentType(url: String): String {
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
      clean.endsWith(".ts") -> "video/mp2t"
      clean.endsWith(".aac") -> "audio/aac"
      clean.endsWith(".m4s") || clean.endsWith(".mp4") -> "video/mp4"
      clean.endsWith(".m4a") -> "audio/mp4"
      clean.endsWith(".webvtt") || clean.endsWith(".vtt") -> "text/vtt"
      clean.endsWith(".m3u8") || clean.endsWith(".m3u") -> MIME_M3U8
      else -> MIME_OCTET
    }
  }

  private fun addCorsHeaders(response: Response) {
    response.addHeader("Access-Control-Allow-Origin", "*")
    response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
    response.addHeader("Access-Control-Allow-Headers", "*")
  }

  private fun notFound(headOnly: Boolean): Response {
    val stream = if (headOnly) ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream("Not Found".toByteArray(StandardCharsets.UTF_8))
    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, stream, if (headOnly) 0L else 9L).apply {
      addCorsHeaders(this)
    }
  }

  private fun generateToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun encodeUrl(url: String): String =
    URLEncoder.encode(url, StandardCharsets.UTF_8.name())

  private fun decodeUrl(encoded: String): String =
    try {
      URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
      encoded
    }
}
