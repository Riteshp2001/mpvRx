/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.proxy

import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.utils.logging.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Response as OkResponse

/** Keeps Xtream credentials behind a capability URL while mpv reads the selected stream. */
class XtreamStreamingProxy private constructor() : NanoHTTPD("127.0.0.1", 0), KoinComponent {
  companion object {
    private const val TAG = "XtreamStreamingProxy"
    private const val TOKEN_BYTES = 24
    private const val RESOLVE_TIMEOUT_SECONDS = 30L
    private const val DEFAULT_USER_AGENT = "mpvRx/2.5"

    @Volatile
    private var instance: XtreamStreamingProxy? = null

    fun getInstance(): XtreamStreamingProxy =
      instance ?: synchronized(this) {
        instance ?: XtreamStreamingProxy().also {
          it.start(SOCKET_READ_TIMEOUT, false)
          instance = it
        }
      }

    fun stopInstance() {
      synchronized(this) {
        instance?.let { proxy ->
          proxy.stop()
          proxy.cleanup()
          instance = null
        }
      }
    }
  }

  private class StreamInfo(
    val reference: XtreamPlaybackUri.Reference,
    val headers: Map<String, String>,
    val mimeType: String,
  ) {
    @Volatile
    var sourceUrl: String? = null
  }

  private class HeadResponse(
    status: Response.IStatus,
    mimeType: String,
    contentLength: Long,
  ) : Response(status, mimeType, ByteArrayInputStream(ByteArray(0)), contentLength)

  private class CustomStatus(
    private val code: Int,
    private val description: String,
  ) : Response.IStatus {
    override fun getRequestStatus(): Int = code

    override fun getDescription(): String = "$code $description"
  }

  private val repository by inject<PlaylistRepository>()
  private val random = SecureRandom()
  private val proxyJob = SupervisorJob()
  private val proxyScope = CoroutineScope(Dispatchers.IO + proxyJob)
  private val tokenByRegistration = ConcurrentHashMap<String, String>()
  private val streamsByToken = ConcurrentHashMap<String, StreamInfo>()
  private val httpClient: OkHttpClient by lazy {
    SharedHttpClient.derive {
      connectTimeout(30L, TimeUnit.SECONDS)
      readTimeout(0L, TimeUnit.MILLISECONDS)
      followRedirects(true)
      followSslRedirects(true)
    }
  }

  @Synchronized
  fun registerStream(
    streamId: String,
    reference: XtreamPlaybackUri.Reference,
    headers: Map<String, String> = emptyMap(),
    mimeType: String = "application/octet-stream",
  ): String {
    val token = generateToken()
    val info = StreamInfo(reference, headers, sanitizeMimeType(mimeType))
    streamsByToken[token] = info
    tokenByRegistration.put(streamId, token)?.let { oldToken ->
      streamsByToken.remove(oldToken)?.let(::release)
    }

    val extension = reference.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    val route = "/xtream/$token/stream$extension"
    return URI("http", null, "127.0.0.1", listeningPort, route, null, null).toASCIIString()
  }

  @Synchronized
  fun unregisterStream(streamId: String) {
    val token = tokenByRegistration.remove(streamId) ?: return
    streamsByToken.remove(token)?.let(::release)
  }

  override fun serve(session: IHTTPSession): Response {
    val headOnly = session.method == Method.HEAD
    if (session.method != Method.GET && session.method != Method.HEAD) {
      return textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed", headOnly)
        .apply { addHeader("Allow", "GET, HEAD") }
    }

    val token = session.uri.removePrefix("/xtream/").substringBefore('/')
    val info = token.takeIf(String::isNotBlank)?.let(streamsByToken::get) ?: return notFound(headOnly)
    val sourceUrl = resolveSource(info) ?: return upstreamFailure(headOnly)

    if (info.reference.extension.equals("m3u8", ignoreCase = true)) {
      return redirectToSource(sourceUrl, headOnly)
    }

    return proxyHttp(session, info, sourceUrl, headOnly)
  }

  private fun resolveSource(info: StreamInfo): String? {
    info.sourceUrl?.let { return it }
    val result = awaitProxyIo { repository.resolveXtreamStream(info.reference) }
    val sourceUrl = result.getOrNull() ?: return null
    synchronized(info) {
      info.sourceUrl?.let { return it }
      info.sourceUrl = sourceUrl
    }
    return sourceUrl
  }

  /** Let mpv load the original manifest so relative segment URLs use the upstream base. */
  private fun redirectToSource(
    sourceUrl: String,
    headOnly: Boolean,
  ): Response =
    textResponse(Response.Status.REDIRECT, "", headOnly).apply { addHeader("Location", sourceUrl) }

  private fun proxyHttp(
    session: IHTTPSession,
    info: StreamInfo,
    sourceUrl: String,
    headOnly: Boolean,
  ): Response =
    try {
      val requestBuilder = Request.Builder().url(sourceUrl)
      info.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
      if (headerValue(info.headers, "User-Agent") == null) {
        requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
      }
      session.headers["range"]?.takeIf(String::isNotBlank)?.let { requestBuilder.header("Range", it) }
      if (headOnly) requestBuilder.head() else requestBuilder.get()

      val upstream = httpClient.newCall(requestBuilder.build()).execute()
      if (isHlsResponse(upstream)) {
        val hlsSourceUrl = upstream.request.url.toString()
        upstream.close()
        redirectToSource(hlsSourceUrl, headOnly)
      } else {
        upstreamResponse(upstream, info.mimeType, headOnly)
      }
    } catch (error: Exception) {
      Log.e(TAG, "Xtream upstream request failed (${error::class.java.simpleName})")
      upstreamFailure(headOnly)
    }

  private fun upstreamResponse(
    upstream: OkResponse,
    fallbackMimeType: String,
    headOnly: Boolean,
  ): Response {
    val status = Response.Status.lookup(upstream.code) ?: CustomStatus(upstream.code, "Upstream Response")
    if (!upstream.isSuccessful) {
      val statusCode = upstream.code
      upstream.close()
      return textResponse(status, "Upstream returned HTTP $statusCode", headOnly)
    }

    val contentType =
      upstream.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
        .takeIf(String::isNotBlank) ?: fallbackMimeType
    val contentLength = upstream.header("Content-Length")?.toLongOrNull() ?: upstream.body.contentLength()
    val response =
      if (headOnly) {
        upstream.close()
        HeadResponse(status, contentType, contentLength.coerceAtLeast(0L))
      } else {
        val bodyStream = ClosingInputStream(upstream.body.byteStream(), upstream)
        if (contentLength >= 0L) {
          newFixedLengthResponse(status, contentType, bodyStream, contentLength)
        } else {
          newChunkedResponse(status, contentType, bodyStream)
        }
      }

    upstream.header("Accept-Ranges")?.let { response.addHeader("Accept-Ranges", it) }
    upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
    upstream.header("Content-Disposition")?.let { response.addHeader("Content-Disposition", it) }
    response.addHeader("Connection", "close")
    return response
  }

  private fun isHlsResponse(upstream: OkResponse): Boolean {
    if (upstream.request.url.encodedPath.endsWith(".m3u8", ignoreCase = true)) return true
    val contentType = upstream.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
    return contentType.contains("mpegurl", ignoreCase = true) || contentType.contains("m3u8", ignoreCase = true)
  }

  private fun <T> awaitProxyIo(operation: suspend () -> Result<T>): Result<T> {
    val result = AtomicReference<Result<T>?>(null)
    val latch = CountDownLatch(1)
    val job =
      proxyScope.launch {
        try {
          result.set(operation())
        } catch (cancellation: CancellationException) {
          result.set(Result.failure(cancellation))
        } catch (error: Exception) {
          result.set(Result.failure(error))
        } finally {
          latch.countDown()
        }
      }
    return try {
      if (latch.await(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        result.get() ?: Result.failure(IOException("Xtream resolution produced no result"))
      } else {
        job.cancel()
        Result.failure(IOException("Xtream resolution timed out"))
      }
    } catch (interrupted: InterruptedException) {
      job.cancel()
      Thread.currentThread().interrupt()
      Result.failure(interrupted)
    }
  }

  private fun release(info: StreamInfo) {
    info.sourceUrl = null
  }

  private fun generateToken(): String {
    var token: String
    do {
      val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    } while (streamsByToken.containsKey(token))
    return token
  }

  private fun sanitizeMimeType(value: String): String =
    value.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*-]+")) }
      ?: "application/octet-stream"

  private fun headerValue(
    headers: Map<String, String>,
    name: String,
  ): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  private fun notFound(headOnly: Boolean): Response =
    textResponse(Response.Status.NOT_FOUND, "Stream not found", headOnly)

  private fun upstreamFailure(headOnly: Boolean): Response =
    textResponse(Response.Status.SERVICE_UNAVAILABLE, "Upstream stream failed", headOnly)

  private fun textResponse(
    status: Response.IStatus,
    message: String,
    headOnly: Boolean,
  ): Response =
    if (headOnly) {
      HeadResponse(status, MIME_PLAINTEXT, message.toByteArray(Charsets.UTF_8).size.toLong())
    } else {
      newFixedLengthResponse(status, MIME_PLAINTEXT, message)
    }

  private fun cleanup() {
    synchronized(this) {
      tokenByRegistration.clear()
      streamsByToken.values.forEach(::release)
      streamsByToken.clear()
    }
    proxyScope.cancel()
  }

  private class ClosingInputStream(
    delegate: InputStream,
    private val response: OkResponse,
  ) : FilterInputStream(delegate) {
    override fun close() {
      try {
        super.close()
      } finally {
        response.close()
      }
    }
  }
}
