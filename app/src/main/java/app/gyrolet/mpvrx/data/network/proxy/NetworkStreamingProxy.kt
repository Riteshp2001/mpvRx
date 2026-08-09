/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.proxy

import android.util.Log
import app.gyrolet.mpvrx.data.network.client.NetworkClient
import app.gyrolet.mpvrx.data.network.client.NetworkMimeTypes
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.repository.NetworkRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback-only authenticated-capability gateway for SMB, FTP, and WebDAV media.
 *
 * Callers keep using their logical [streamId] for unregistering, but it is never exposed on the
 * HTTP endpoint. Every registration gets a cryptographically random capability token. A token can
 * resolve sibling paths below the configured connection root so relative HLS manifests, segments,
 * keys, and maps keep working without embedding remote credentials in a URL.
 */
class NetworkStreamingProxy private constructor() :
  NanoHTTPD("127.0.0.1", 0),
  KoinComponent {
  companion object {
    private const val TAG = "NetworkStreamingProxy"
    private const val TOKEN_BYTES = 24

    @Volatile
    private var instance: NetworkStreamingProxy? = null

    fun getInstance(): NetworkStreamingProxy =
      instance ?: synchronized(this) {
        instance ?: NetworkStreamingProxy().also {
          it.start()
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
    val connectionId: Long,
    val primaryPath: NetworkPath,
    fileSize: Long,
    val primaryMimeType: String,
  ) {
    val knownPrimarySize = AtomicLong(fileSize)
    val clientMutex = Mutex()

    @Volatile
    var client: NetworkClient? = null
  }

  private class HeadResponse(
    status: Response.IStatus,
    mimeType: String,
    contentLength: Long,
  ) : Response(status, mimeType, ByteArrayInputStream(ByteArray(0)), contentLength)

  private val repository by inject<NetworkRepository>()
  private val random = SecureRandom()
  private val proxyJob = SupervisorJob()
  private val proxyScope = CoroutineScope(Dispatchers.IO + proxyJob)
  private val tokenByRegistration = ConcurrentHashMap<String, String>()
  private val streamsByToken = ConcurrentHashMap<String, StreamInfo>()
  private val closingStreams = ConcurrentHashMap.newKeySet<StreamInfo>()

  /** Registers a caller-owned logical stream and returns a credential-free loopback URL. */
  @Synchronized
  fun registerStream(
    streamId: String,
    connection: NetworkConnection,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "video/mp4",
  ): String =
    registerStream(
      streamId = streamId,
      connectionId = connection.id,
      filePath = filePath,
      fileSize = fileSize,
      mimeType = mimeType,
    )

  /** Registers a stream by saved connection id without exposing or loading its credentials. */
  @Synchronized
  fun registerStream(
    streamId: String,
    connectionId: Long,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "video/mp4",
  ): String {
    require(connectionId > 0L) { "A saved network connection is required for proxy streaming" }
    require(fileSize >= -1L) { "File size must be -1 (unknown) or non-negative" }

    val path = NetworkPath.from(filePath)
    val token = generateToken()
    val streamInfo =
      StreamInfo(
        connectionId = connectionId,
        primaryPath = path,
        fileSize = fileSize,
        primaryMimeType = sanitizeMimeType(mimeType),
      )

    streamsByToken[token] = streamInfo
    tokenByRegistration.put(streamId, token)?.let { previousToken ->
      streamsByToken.remove(previousToken)?.let(::closeAsync)
    }

    val route = "/$token${path.value}"
    return URI("http", null, "127.0.0.1", listeningPort, route, null, null).toASCIIString()
  }

  /** Unregisters the logical ID supplied to [registerStream]. */
  @Synchronized
  fun unregisterStream(streamId: String) {
    val token = tokenByRegistration.remove(streamId) ?: return
    streamsByToken.remove(token)?.let(::closeAsync)
  }

  override fun serve(session: IHTTPSession): Response {
    val headOnly = session.method == Method.HEAD
    val route = parseRoute(session.uri) ?: return notFound(headOnly)
    val streamInfo = streamsByToken[route.token] ?: return notFound(headOnly)
    val requestedPath = route.path ?: streamInfo.primaryPath

    if (session.method != Method.GET && session.method != Method.HEAD) {
      return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        .apply { addHeader("Allow", "GET, HEAD") }
    }

    return try {
      val rangeHeader = session.headers["range"]
      if (rangeHeader == null) {
        handleFullRequest(headOnly, streamInfo, requestedPath)
      } else {
        handleRangeRequest(headOnly, streamInfo, requestedPath, rangeHeader)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      // Do not include remote URLs, paths, credentials, or exception messages in logs/responses.
      Log.e(TAG, "Proxy request failed (${error::class.java.simpleName})")
      textResponse(Response.Status.INTERNAL_ERROR, "Upstream stream failed", headOnly)
    }
  }

  private fun handleRangeRequest(
    headOnly: Boolean,
    streamInfo: StreamInfo,
    path: NetworkPath,
    rangeHeader: String,
  ): Response {
    val fileSize = getFileSize(streamInfo, path)
    if (fileSize < 0L) return upstreamFailure(headOnly)
    val range = HttpByteRange.parse(rangeHeader, fileSize) ?: return rangeNotSatisfiable(fileSize, headOnly)
    val mimeType = mimeTypeFor(streamInfo, path)

    val response =
      if (headOnly) {
        HeadResponse(Response.Status.PARTIAL_CONTENT, mimeType, range.length)
      } else {
        val inputStream = getStream(streamInfo, path, range.start)
          ?: return upstreamFailure(headOnly)
        newFixedLengthResponse(
          Response.Status.PARTIAL_CONTENT,
          mimeType,
          inputStream,
          range.length,
        )
      }

    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$fileSize")
    return response
  }

  private fun handleFullRequest(
    headOnly: Boolean,
    streamInfo: StreamInfo,
    path: NetworkPath,
  ): Response {
    val fileSize = getFileSize(streamInfo, path)
    val mimeType = mimeTypeFor(streamInfo, path)

    if (headOnly && fileSize < 0L) return upstreamFailure(headOnly)
    if (headOnly) {
      return HeadResponse(Response.Status.OK, mimeType, fileSize).apply {
        addHeader("Accept-Ranges", "bytes")
      }
    }
    if (fileSize == 0L) {
      return emptyResponse(Response.Status.OK, mimeType).apply { addHeader("Accept-Ranges", "bytes") }
    }

    val inputStream = getStream(streamInfo, path, 0L) ?: return upstreamFailure(headOnly)
    return if (fileSize >= 0L) {
      newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, fileSize).apply {
        addHeader("Accept-Ranges", "bytes")
      }
    } else {
      newChunkedResponse(Response.Status.OK, mimeType, inputStream)
    }
  }

  private fun getFileSize(
    streamInfo: StreamInfo,
    path: NetworkPath,
  ): Long {
    if (path == streamInfo.primaryPath) {
      streamInfo.knownPrimarySize.get().takeIf { it >= 0L }?.let { return it }
    }

    val discovered =
      runBlocking {
        withConnectedClient(streamInfo) { client -> client.getFileSize(path.value) }
      }.getOrNull() ?: -1L

    if (path == streamInfo.primaryPath && discovered >= 0L) {
      streamInfo.knownPrimarySize.compareAndSet(-1L, discovered)
    }
    return discovered
  }

  private fun getStream(
    streamInfo: StreamInfo,
    path: NetworkPath,
    offset: Long,
  ): InputStream? =
    runBlocking {
      withConnectedClient(streamInfo) { client -> client.getFileStream(path.value, offset) }
    }.getOrNull()

  private suspend fun <T> withConnectedClient(
    streamInfo: StreamInfo,
    operation: suspend (NetworkClient) -> Result<T>,
  ): Result<T> =
    streamInfo.clientMutex.withLock {
      var client = streamInfo.client
      if (client == null) {
        val candidate = repository.createClient(streamInfo.connectionId).getOrElse { return@withLock Result.failure(it) }
        try {
          candidate.connect().getOrThrow()
          streamInfo.client = candidate
          client = candidate
        } catch (cancellation: CancellationException) {
          runCatching { candidate.disconnect() }
          throw cancellation
        } catch (error: Exception) {
          runCatching { candidate.disconnect() }
          return@withLock Result.failure(error)
        }
      } else {
        val existingClient = client
        if (!existingClient.isConnected()) {
          try {
            existingClient.connect().getOrThrow()
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (error: Exception) {
            streamInfo.client = null
            runCatching { existingClient.disconnect() }
            return@withLock Result.failure(error)
          }
        }
      }

      operation(client)
    }

  private data class Route(
    val token: String,
    val path: NetworkPath?,
  )

  private fun parseRoute(uri: String): Route? {
    val withoutLeadingSlash = uri.removePrefix("/")
    val token = withoutLeadingSlash.substringBefore('/').takeIf(String::isNotBlank) ?: return null
    val pathText = withoutLeadingSlash.substringAfter('/', missingDelimiterValue = "")
    val path =
      if (pathText.isBlank()) {
        null
      } else {
        runCatching { NetworkPath.from(pathText) }.getOrNull() ?: return null
      }
    return Route(token, path)
  }

  private fun getKnownRegistrationMime(
    streamInfo: StreamInfo,
    path: NetworkPath,
  ): String? = streamInfo.primaryMimeType.takeIf { path == streamInfo.primaryPath }

  private fun mimeTypeFor(
    streamInfo: StreamInfo,
    path: NetworkPath,
  ): String =
    getKnownRegistrationMime(streamInfo, path)
      ?: NetworkMimeTypes.forFileName(path.relative)
      ?: "application/octet-stream"

  private fun sanitizeMimeType(value: String): String =
    value.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*-]+")) }
      ?: "application/octet-stream"

  private fun generateToken(): String {
    var token: String
    do {
      val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    } while (streamsByToken.containsKey(token))
    return token
  }

  private fun emptyResponse(
    status: Response.IStatus,
    mimeType: String,
  ): Response = newFixedLengthResponse(status, mimeType, ByteArrayInputStream(ByteArray(0)), 0L)

  private fun rangeNotSatisfiable(
    fileSize: Long,
    headOnly: Boolean,
  ): Response =
    textResponse(
      Response.Status.RANGE_NOT_SATISFIABLE,
      "Requested range not satisfiable",
      headOnly,
    ).apply {
      addHeader("Content-Range", "bytes */$fileSize")
      addHeader("Accept-Ranges", "bytes")
    }

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

  private fun closeAsync(streamInfo: StreamInfo) {
    if (!closingStreams.add(streamInfo)) return
    proxyScope.launch {
      try {
        close(streamInfo)
      } finally {
        closingStreams.remove(streamInfo)
      }
    }
  }

  private suspend fun close(streamInfo: StreamInfo) =
    withContext(NonCancellable) {
      streamInfo.clientMutex.withLock {
        val client = streamInfo.client
        streamInfo.client = null
        runCatching { client?.disconnect() }
      }
    }

  private fun cleanup() {
    val streams =
      synchronized(this) {
        tokenByRegistration.clear()
        (streamsByToken.values + closingStreams).distinct().also {
          streamsByToken.clear()
          closingStreams.clear()
        }
      }

    proxyScope.launch {
      streams.forEach { close(it) }
      proxyScope.cancel()
    }
  }
}
