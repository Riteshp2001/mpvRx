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
import app.gyrolet.mpvrx.domain.network.encodeNetworkPathForUrl
import app.gyrolet.mpvrx.repository.NetworkRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private const val PROXY_OPERATION_TIMEOUT_SECONDS = 75L
    private const val RANGE_CHUNK_BYTES = 8L * 1024 * 1024
    private const val RANGE_CHUNK_CACHE_BYTES = 48L * 1024 * 1024
    // Disc ISO files need many small, unpredictable sector reads. Avoid filling multi-megabyte
    // chunks for each probe and limit the resident cache on memory-constrained devices.
    private const val ISO_RANGE_CHUNK_BYTES = 1L * 1024 * 1024
    private const val ISO_RANGE_CHUNK_CACHE_BYTES = 16L * 1024 * 1024
    private const val RANGE_CHUNK_READ_TIMEOUT_MS = 45_000L
    private const val CHUNK_TRANSFER_BUFFER_BYTES = 64 * 1024
    private const val RANGE_CONTIGUITY_BYTES = 16L * 1024 * 1024
    private const val RANGE_MAX_CLUSTERS = 2
    private const val RANGE_CLUSTER_TTL_NANOS = 10_000_000_000L

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
    // Cache sizes for the primary media and sibling resources (HLS segments, keys, maps, etc.).
    // A seek can otherwise issue repeated serialized size probes before every range request.
    val knownSizes = ConcurrentHashMap<NetworkPath, Long>().apply {
      if (fileSize >= 0L) put(primaryPath, fileSize)
    }
    val isDiscImage = primaryPath.value.endsWith(".iso", ignoreCase = true)
    val rangeChunkBytes = if (isDiscImage) ISO_RANGE_CHUNK_BYTES else RANGE_CHUNK_BYTES
    val chunkCache = RangeChunkCache(maxResidentBytes = if (isDiscImage) ISO_RANGE_CHUNK_CACHE_BYTES else RANGE_CHUNK_CACHE_BYTES)
    val clientMutex = Mutex()

    private class RangeCluster(
      var center: Long,
      var lastSeenNanos: Long,
    )

    private val rangeClusters = ArrayList<RangeCluster>()

    /**
     * Groups nearby range starts into clusters (the player reads a non-interleaved file from two
     * or more distant regions at once). A start that matches no live cluster while all cluster
     * slots are taken is a real seek.
     */
    @Synchronized
    fun noteRangeStart(start: Long): Boolean {
      val now = System.nanoTime()
      rangeClusters.removeAll { now - it.lastSeenNanos > RANGE_CLUSTER_TTL_NANOS }
      val existing = rangeClusters.firstOrNull { kotlin.math.abs(start - it.center) <= RANGE_CONTIGUITY_BYTES }
      if (existing != null) {
        existing.center = start
        existing.lastSeenNanos = now
        return false
      }
      if (rangeClusters.size < RANGE_MAX_CLUSTERS) {
        rangeClusters.add(RangeCluster(start, now))
        return false
      }
      rangeClusters.clear()
      rangeClusters.add(RangeCluster(start, now))
      return true
    }

    @Volatile
    var client: NetworkClient? = null

    @Volatile
    var closed: Boolean = false
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
    return "http://127.0.0.1:$listeningPort${encodeNetworkPathForUrl(route)}"
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
      val response =
        if (rangeHeader == null) {
          handleFullRequest(headOnly, streamInfo, requestedPath)
        } else {
          handleRangeRequest(headOnly, streamInfo, requestedPath, rangeHeader)
        }
      // NanoHTTPD keeps a fixed-length socket alive even when the upstream body ends short,
      // leaving the player waiting forever for the missing bytes. Closing per response turns
      // that into a visible disconnect that mpv's reconnect logic recovers from.
      response.addHeader("Connection", "close")
      response
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
    val range = HttpByteRange.parse(rangeHeader, fileSize) ?: return rangeNotSatisfiable(fileSize)
    val mimeType = mimeTypeFor(streamInfo, path)
    // ISO readers issue short, bounded byte ranges for sector probes, not only open-ended
    // ranges. Cache those probes as well; keep legacy behavior for ordinary media/HLS.
    val isCacheablePrimaryRange =
      path == streamInfo.primaryPath && (range.openEnded || streamInfo.isDiscImage)
    val isSeek = !headOnly && !streamInfo.isDiscImage && isCacheablePrimaryRange &&
      streamInfo.noteRangeStart(range.start)
    if (isSeek) {
      // A scrub jumps far away from every recent request; drop fills for the positions that were
      // already abandoned so the new target (or the final release position) fills first.
      streamInfo.chunkCache.cancelInProgressFills()
    }

    val response =
      if (headOnly) {
        HeadResponse(Response.Status.PARTIAL_CONTENT, mimeType, range.length)
      } else {
        val inputStream =
          if (isCacheablePrimaryRange) {
            // mpv alternates between distant video and audio regions in non-interleaved files.
            // Serve the opening chunk from a resident copy so those repeated random reads do not
            // each pay the store's per-request latency.
            ChunkedRangeInputStream(streamInfo, path, fileSize, range.start, range.endInclusive)
          } else {
            getStream(streamInfo, path, range.start) ?: return upstreamFailure(headOnly)
          }
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
    streamInfo.knownSizes[path]?.let { return it }

    val discovered =
      awaitProxyIo {
        withConnectedClient(streamInfo) { client -> client.getFileSize(path.value) }
      }.getOrNull() ?: -1L

    if (discovered >= 0L) {
      streamInfo.knownSizes.putIfAbsent(path, discovered)
    }
    return discovered
  }

  private fun getStream(
    streamInfo: StreamInfo,
    path: NetworkPath,
    offset: Long,
  ): InputStream? =
    awaitProxyIo {
      withConnectedClient(streamInfo) { client -> client.getFileStream(path.value, offset) }
    }.getOrNull()

  /**
   * Serves an open-ended range request from progressively filled chunks.
   *
   * The opening chunk starts filling in the background and its bytes are forwarded as they arrive,
   * so a seek does not wait for a whole chunk. Requests that land in a resident chunk are served
   * from memory; anything else streams directly from upstream inside the same response, keeping
   * Content-Length and EOF exactly as NanoHTTPD would have produced without the cache.
   */
  private inner class ChunkedRangeInputStream(
    private val streamInfo: StreamInfo,
    private val path: NetworkPath,
    private val fileSize: Long,
    start: Long,
    private val endInclusive: Long,
  ) : InputStream() {
    private var position = start
    private val initialPosition = start
    private val initialChunkIndex = start / streamInfo.rangeChunkBytes
    private var upstream: InputStream? = null
    private var upstreamPosition = -1L
    private var closed = false
    private val singleByte = ByteArray(1)

    override fun read(): Int {
      val count = read(singleByte, 0, 1)
      return if (count < 0) -1 else singleByte[0].toInt() and 0xFF
    }

    override fun read(
      b: ByteArray,
      off: Int,
      len: Int,
    ): Int {
      if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
      if (len == 0) return 0
      if (closed || position > endInclusive) return -1

      val maxRead = minOf(len.toLong(), endInclusive - position + 1L).toInt()
      val index = position / streamInfo.rangeChunkBytes
      // Only the chunk the response starts in is filled on demand. Later chunks are served from
      // prefetch when resident; otherwise the response streams directly from upstream so
      // sequential playback never waits on a fill.
      val candidate =
        streamInfo.chunkCache.get(path, index)
          ?: if (index == initialChunkIndex) obtainChunk(index, position) else null
      val chunk = candidate?.takeIf { position >= it.start && position - it.start < it.capacity }
      if (chunk != null) {
        val offsetInChunk = (position - chunk.start).toInt()
        // A different request may have started filling this bin earlier. Never wait through its
        // unread prefix before serving a new seek/resume position; open that exact offset instead.
        if ((position == initialPosition || upstream != null) &&
          offsetInChunk > 0 && chunk.availableBytes <= offsetInChunk
        ) {
          return readUpstream(b, off, maxRead)
        }
        val available =
          runCatching {
            chunk.awaitAvailable(
              offsetInChunk + 1,
              System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RANGE_CHUNK_READ_TIMEOUT_MS),
            )
          }.getOrDefault(-1)
        if (available > offsetInChunk) {
          val count = chunk.copyTo(offsetInChunk, b, off, maxRead)
          if (count > 0) {
            position += count
            if (position - chunk.start >= chunk.capacity / 2) {
              prefetchChunk(index + 1)
            }
            return count
          }
        }
      }
      return readUpstream(b, off, maxRead)
    }

    override fun close() {
      if (closed) return
      closed = true
      closeUpstream()
    }

    private fun obtainChunk(index: Long, start: Long): RangeChunkCache.Chunk? {
      if (start >= fileSize) return null
      // Keep fixed bin keys, but fill from the requested byte, not up to 8 MiB before it.
      val offsetInBin = start % streamInfo.rangeChunkBytes
      return runCatching {
        streamInfo.chunkCache.obtain(
          path,
          index,
          start,
          minOf(streamInfo.rangeChunkBytes - offsetInBin, fileSize - start).toInt(),
          proxyScope,
        ) { chunk, context -> fillChunk(streamInfo, path, chunk, context) }
      }.getOrNull()
    }

    private fun readUpstream(
      b: ByteArray,
      off: Int,
      len: Int,
    ): Int {
      if (upstream == null || upstreamPosition != position) {
        closeUpstream()
        val opened = getStream(streamInfo, path, position)
          ?: throw IOException("Upstream stream failed while serving a cached range")
        upstream = opened
        upstreamPosition = position
      }
      val count = upstream!!.read(b, off, len)
      if (count > 0) {
        position += count
        upstreamPosition = position
      }
      return count
    }

    private fun closeUpstream() {
      upstream?.let { stream -> runCatching { stream.close() } }
      upstream = null
      upstreamPosition = -1L
    }

    private fun prefetchChunk(index: Long) {
      val start = index * streamInfo.rangeChunkBytes
      if (start >= fileSize) return
      streamInfo.chunkCache.prefetch(
        proxyScope,
        path,
        index,
        start,
        minOf(streamInfo.rangeChunkBytes, fileSize - start).toInt(),
      ) { chunk, context -> fillChunk(streamInfo, path, chunk, context) }
    }
  }

  private suspend fun fillChunk(
    streamInfo: StreamInfo,
    path: NetworkPath,
    chunk: RangeChunkCache.Chunk,
    context: RangeChunkCache.FillContext,
  ) {
    val opened =
      withConnectedClient(streamInfo) { client -> client.getFileStream(path.value, chunk.start) }
    val source = opened.getOrElse { error -> throw IOException("Upstream chunk open failed", error) }
    source.use { stream ->
      if (!context.attach(stream)) throw IOException("Chunk fill cancelled")
      try {
        val buffer = ByteArray(CHUNK_TRANSFER_BUFFER_BYTES)
        var remaining = chunk.capacity
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RANGE_CHUNK_READ_TIMEOUT_MS)
        while (remaining > 0) {
          context.throwIfCancelled()
          if (System.nanoTime() - deadlineNanos > 0L) throw IOException("Timed out reading an upstream chunk")
          val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
          if (count < 0) break
          if (count == 0) continue
          chunk.append(buffer, 0, count)
          remaining -= count
        }
      } finally {
        context.detach(stream)
      }
    }
  }

  /**
   * NanoHTTPD's serve API is synchronous, but upstream clients are suspend-based. Do not use
   * runBlocking here: it installs a nested coroutine event loop on every range request and can
   * amplify thread contention under rapid seeks. Dispatch the suspend work onto the proxy's bounded
   * IO scope and wait only for the result, with a hard timeout and cancellation.
   */
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

    // An abandoned operation can still complete and hand back an open stream; close it so a
    // slow server can't permanently eat one of its own connection slots per timed-out request.
    fun discardLateResult() {
      job.invokeOnCompletion {
        (result.get()?.getOrNull() as? InputStream)?.let { stream -> runCatching(stream::close) }
      }
      job.cancel()
    }

    return try {
      if (!latch.await(PROXY_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        discardLateResult()
        Result.failure(IOException("Upstream proxy operation timed out"))
      } else {
        result.get() ?: Result.failure(IOException("Upstream proxy operation produced no result"))
      }
    } catch (interrupted: InterruptedException) {
      discardLateResult()
      Thread.currentThread().interrupt()
      Result.failure(interrupted)
    }
  }

  private suspend fun <T> withConnectedClient(
    streamInfo: StreamInfo,
    operation: suspend (NetworkClient) -> Result<T>,
  ): Result<T> {
    val client = ensureClient(streamInfo).getOrElse { return Result.failure(it) }
    if (streamInfo.closed) return Result.failure(IOException("Network stream is no longer registered"))
    // The operation runs outside the mutex: opening the video and audio chunks at once must not
    // serialize the two upstream requests behind each other.
    val result = operation(client)
    if (streamInfo.closed) {
      // An open may have raced unregistration and reconnected inside the protocol client.
      // Discard both the late result and that transport instead of leaving an orphan session.
      withContext(NonCancellable) {
        runCatching { client.disconnect() }.onFailure { error ->
          Log.w(TAG, "Failed to disconnect abandoned upstream client (${error::class.java.simpleName})")
        }
        (result.getOrNull() as? InputStream)?.let { stream ->
          runCatching { stream.close() }.onFailure { error ->
            Log.w(TAG, "Failed to close abandoned upstream stream (${error::class.java.simpleName})")
          }
        }
      }
      return Result.failure(IOException("Network stream was unregistered during the upstream operation"))
    }
    result.exceptionOrNull()?.let { error ->
      if (error is CancellationException) throw error
      // Client error messages are app-constructed (HTTP status etc.) and carry no URLs/credentials.
      Log.w(TAG, "Upstream operation failed (${error::class.java.simpleName}: ${error.message})")
      // A dead session would otherwise be reused forever; evict it so the next request
      // reconnects instead of failing every range request until the stream is re-registered.
      if (!client.isConnected()) {
        streamInfo.clientMutex.withLock {
          if (streamInfo.client === client) {
            streamInfo.client = null
            runCatching { client.disconnect() }
          }
        }
      }
    }
    return result
  }

  private suspend fun ensureClient(streamInfo: StreamInfo): Result<NetworkClient> =
    streamInfo.clientMutex.withLock {
      if (streamInfo.closed) return@withLock Result.failure(IOException("Network stream is no longer registered"))
      var client = streamInfo.client
      if (client == null) {
        val candidate =
          repository.createClient(streamInfo.connectionId).getOrElse { error ->
            Log.w(TAG, "Upstream client creation failed (${error::class.java.simpleName})")
            return@withLock Result.failure(error)
          }
        try {
          candidate.connect().getOrThrow()
          if (streamInfo.closed) throw IOException("Network stream was unregistered during connection")
          streamInfo.client = candidate
          client = candidate
        } catch (cancellation: CancellationException) {
          runCatching { candidate.disconnect() }
          throw cancellation
        } catch (error: Exception) {
          Log.w(TAG, "Upstream connect failed (${error::class.java.simpleName})")
          runCatching { candidate.disconnect() }
          return@withLock Result.failure(error)
        }
      } else {
        val existingClient = client
        if (!existingClient.isConnected()) {
          try {
            existingClient.connect().getOrThrow()
            if (streamInfo.closed) throw IOException("Network stream was unregistered during reconnection")
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (error: Exception) {
            Log.w(TAG, "Upstream reconnect failed (${error::class.java.simpleName})")
            streamInfo.client = null
            runCatching { existingClient.disconnect() }
            return@withLock Result.failure(error)
          }
        }
      }
      Result.success(client)
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

  private fun rangeNotSatisfiable(fileSize: Long): Response =
    emptyResponse(Response.Status.RANGE_NOT_SATISFIABLE, "application/octet-stream").apply {
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
    streamInfo.closed = true
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
      streamInfo.closed = true
      // Terminate transport reads before closing their buffered streams: close() can otherwise
      // wait on the same monitor as a blocked read, keeping an old SMB transfer alive on reopen.
      streamInfo.clientMutex.withLock {
        val client = streamInfo.client
        streamInfo.client = null
        runCatching { client?.disconnect() }.onFailure { error ->
          Log.w(TAG, "Failed to disconnect released upstream client (${error::class.java.simpleName})")
        }
      }
      streamInfo.chunkCache.dispose()
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
