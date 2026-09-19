/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.client

import android.net.Uri
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.network.SharedHttpClient
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class WebDavClient(
  private val connection: NetworkConnection,
) : NetworkClient {
  companion object {
    private const val SKIP_BUFFER_BYTES = 64 * 1024
    private val rangeHttpClient by lazy {
      SharedHttpClient.derive {
        // A call timeout covers the entire response body and would terminate healthy long streams.
        // Keep the shared connect/read timeouts, which still detect connection and socket stalls.
        callTimeout(0, TimeUnit.SECONDS)
      }
    }
    private val contentRangePattern = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    private val encodedPathSeparatorPattern = Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE)
  }

  private data class RangedResponse(
    val response: Response,
    val start: Long,
    val endInclusive: Long?,
    val totalLength: Long?,
  )

  private var sardine: Sardine? = null

  /**
   * Builds WebDAV request URLs from decoded path segments. HttpUrl owns the wire encoding so
   * reserved filename characters such as '[', ']', '%', '#', '?' and spaces are encoded exactly
   * once and are never reparsed through java.net.URI.
   */
  private fun buildHttpUrl(
    relativePath: String,
    trailingSlash: Boolean = false,
  ): HttpUrl {
    val host = connection.host.trim().removePrefix("[").removeSuffix("]")
    val builder =
      HttpUrl
        .Builder()
        .scheme(if (connection.useHttps) "https" else "http")
        .host(host)
        .port(connection.port)

    NetworkPath.from(connection.path).segments.forEach(builder::addPathSegment)
    NetworkPath.from(relativePath).segments.forEach(builder::addPathSegment)
    if (trailingSlash) builder.addPathSegment("")
    return builder.build()
  }

  private fun buildUrl(
    relativePath: String,
    trailingSlash: Boolean = false,
  ): String = buildHttpUrl(relativePath, trailingSlash).toString()

  /**
   * Uses Sardine's parsed DavResource href as the source of truth for child identity. Sardine has
   * already URI-decoded the href path once; decoding it again corrupts literal percent sequences.
   * Relative hrefs are resolved against the requested collection path without creating another URI.
   */
  private fun toImmediateChild(
    resource: DavResource,
    directory: NetworkPath,
    directoryUrl: HttpUrl,
  ): NetworkFile? {
    val href = resource.href
    if (href.query != null || href.fragment != null) return null
    if (href.rawPath?.let(encodedPathSeparatorPattern::containsMatchIn) == true) return null

    val requestedSegments = directoryUrl.pathSegments.filter(String::isNotEmpty)
    val resolvedSegments =
      directoryUrl
        .resolve(href.toASCIIString())
        ?.pathSegments
        ?.filter(String::isNotEmpty)
        ?: return null

    if (resolvedSegments == requestedSegments) return null
    val exactChildName =
      resolvedSegments
        .takeIf { segments ->
          segments.size == requestedSegments.size + 1 &&
            segments.take(requestedSegments.size) == requestedSegments
        }?.last()

    // Reverse proxies sometimes rewrite the collection prefix in response hrefs. Sardine's name
    // remains the decoded final path component, so use it only when exact URI resolution cannot
    // identify the child. Exclude a rewritten collection-self response by its trailing directory.
    val fallbackName = resource.name?.trimEnd('/')?.takeIf(String::isNotBlank)
    if (
      exactChildName == null &&
      resource.isDirectory &&
      fallbackName == requestedSegments.lastOrNull() &&
      resolvedSegments.lastOrNull() == requestedSegments.lastOrNull()
    ) {
      return null
    }

    val childName = exactChildName ?: fallbackName ?: return null
    return runCatching {
      val filePath = directory.child(childName)
      val displayName = resource.name?.takeIf(String::isNotBlank) ?: childName
      NetworkFile(
        name = displayName,
        path = filePath.value,
        isDirectory = resource.isDirectory,
        size = resource.contentLength ?: -1L,
        lastModified = resource.modified?.time ?: 0,
        mimeType = if (!resource.isDirectory) NetworkMimeTypes.forFileName(displayName) else null,
      )
    }.getOrNull()
  }

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val candidate = OkHttpSardine()
        if (!connection.isAnonymous) {
          candidate.setCredentials(connection.username, connection.password)
        }

        // Reachability/credential probe only. Reverse proxies commonly reject or empty out a
        // depth-0 PROPFIND at the share root while every file below it stays fully streamable,
        // so only credential rejections are conclusive failures here.
        try {
          candidate.list(buildUrl("", trailingSlash = true), 0)
        } catch (probeError: SardineException) {
          if (probeError.statusCode == 401 || probeError.statusCode == 403) {
            throw NetworkAuthenticationException("WebDAV rejected the saved credentials", probeError)
          }
        }

        sardine = candidate
        Result.success(Unit)
      } catch (cancellation: CancellationException) {
        sardine = null
        throw cancellation
      } catch (error: Exception) {
        sardine = null
        Result.failure(error)
      }
    }

  override suspend fun disconnect() {
    withContext(Dispatchers.IO) {
      sardine = null
    }
  }

  override fun isConnected(): Boolean = sardine != null

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      try {
        val client = sardine ?: return@withContext Result.failure(IOException("Not connected"))
        val directory = NetworkPath.from(path)
        val directoryUrl = buildHttpUrl(directory.value, trailingSlash = true)
        val resources = client.list(directoryUrl.toString())

        val files =
          resources
            .mapNotNull { resource -> toImmediateChild(resource, directory, directoryUrl) }
            // Some DAV servers emit the same href more than once with different propstat blocks.
            .distinctBy(NetworkFile::path)

        Result.success(files)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      try {
        val client = sardine ?: return@withContext Result.failure(IOException("Not connected"))
        val filePath = NetworkPath.from(path)
        val resource = runCatching { client.list(buildUrl(filePath.value), 0) }.getOrNull()?.firstOrNull()
        if (resource?.isDirectory == true) {
          Result.failure(IOException("File not found or is a directory"))
        } else {
          // Hybrid HTTP/DAV servers can reject PROPFIND on files or omit getcontentlength
          // while still serving GET/HEAD, so fall back to an HTTP size probe.
          val size = resource?.contentLength?.takeIf { it >= 0L } ?: probeSizeOverHttp(filePath)
          if (size == null || size < 0L) {
            Result.failure(IOException("WebDAV server did not provide a file size"))
          } else {
            Result.success(size)
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun probeSizeOverHttp(path: NetworkPath): Long? {
    val headBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .head()
        .header("Accept-Encoding", "identity")
    if (!connection.isAnonymous) {
      headBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }
    rangeHttpClient.newCall(headBuilder.build()).execute().use { response ->
      if (response.isSuccessful) {
        response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
      }
    }

    // Servers that omit a HEAD Content-Length still report the total in a ranged Content-Range.
    val rangeBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .get()
        .header("Range", "bytes=0-0")
        .header("Accept-Encoding", "identity")
    if (!connection.isAnonymous) {
      rangeBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }
    rangeHttpClient.newCall(rangeBuilder.build()).execute().use { response ->
      val match = contentRangePattern.matchEntire(response.header("Content-Range").orEmpty())
      return match?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()
    }
  }

  override suspend fun getFileStream(
    path: String,
    offset: Long,
  ): Result<InputStream> =
    withContext(Dispatchers.IO) {
      require(offset >= 0L) { "Stream offset must not be negative" }
      try {
        getRangedFileStream(NetworkPath.from(path), offset)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun getRangedFileStream(
    path: NetworkPath,
    offset: Long,
  ): Result<InputStream> {
    val initial =
      try {
        openRangedResponse(path, offset)
      } catch (error: Exception) {
        return Result.failure(error)
      }

    return Result.success(
      object : InputStream() {
        private var current = initial
        private var stream = current.response.body.byteStream()
        private var position = current.start
        private var bytesRemaining = current.endInclusive?.let { it - current.start + 1L }
        private var totalLength = current.totalLength
        private var closed = false

        override fun read(): Int {
          val singleByte = ByteArray(1)
          val count = read(singleByte, 0, 1)
          return if (count < 0) -1 else singleByte[0].toInt() and 0xff
        }

        override fun read(
          b: ByteArray,
          off: Int,
          len: Int,
        ): Int {
          if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
          if (len == 0) return 0
          check(!closed) { "WebDAV range stream is closed" }

          while (true) {
            if (bytesRemaining == 0L) {
              val completeLength = totalLength ?: return -1
              if (position >= completeLength) return -1

              current.response.close()
              val next = openRangedResponse(path, position)
              if (next.totalLength != null && next.totalLength != completeLength) {
                next.response.close()
                throw IOException("WebDAV resource length changed during streaming")
              }
              current = next
              stream = next.response.body.byteStream()
              bytesRemaining = next.endInclusive?.let { it - next.start + 1L }
              totalLength = next.totalLength ?: completeLength
            }

            val toRead = bytesRemaining?.let { minOf(len.toLong(), it).toInt() } ?: len
            val count = stream.read(b, off, toRead)
            if (count > 0) {
              position += count
              bytesRemaining = bytesRemaining?.minus(count)
              return count
            }
            if (count == 0) continue
            if (bytesRemaining == null) return -1
            throw IOException("WebDAV range response ended before its declared Content-Range")
          }
        }

        override fun available(): Int =
          if (closed) {
            0
          } else {
            bytesRemaining
              ?.let { minOf(stream.available().toLong(), it, Int.MAX_VALUE.toLong()).toInt() }
              ?: stream.available()
          }

        override fun close() {
          if (closed) return
          closed = true
          current.response.close()
        }
      },
    )
  }

  private fun openRangedResponse(
    path: NetworkPath,
    offset: Long,
  ): RangedResponse {
    val requestBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .get()
        .header("Range", "bytes=$offset-")
        .header("Accept-Encoding", "identity")

    if (!connection.isAnonymous) {
      requestBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }

    val response = rangeHttpClient.newCall(requestBuilder.build()).execute()
    val rangeMatch = contentRangePattern.matchEntire(response.header("Content-Range").orEmpty())
    val returnedStart = rangeMatch?.groupValues?.get(1)?.toLongOrNull()
    val returnedEnd = rangeMatch?.groupValues?.get(2)?.toLongOrNull()
    val totalLength = rangeMatch?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()
    val returnedLength =
      if (returnedStart != null && returnedEnd != null && returnedEnd >= returnedStart) {
        returnedEnd - returnedStart + 1L
      } else {
        null
      }
    val bodyLength = response.body.contentLength()

    // Some DAV servers ignore Range and reply 200 with the full body. Consuming up to the offset
    // keeps seeking functional there; slow for deep seeks, but strictly better than failing.
    if (response.code == 200 && (bodyLength < 0L || bodyLength >= offset)) {
      try {
        skipExactly(response.body.byteStream(), offset)
      } catch (error: Exception) {
        response.close()
        throw error
      }
      return RangedResponse(
        response = response,
        start = offset,
        endInclusive = bodyLength.takeIf { it >= 0L }?.minus(1L),
        totalLength = bodyLength.takeIf { it >= 0L },
      )
    }

    // A bare HTTP 200 means the server ignored Range. Returning it as if it started at
    // [offset] corrupts seeking, so only a validated 206 response is accepted.
    if (
      response.code != 206 ||
      returnedStart != offset ||
      returnedEnd == null ||
      returnedEnd < offset ||
      (bodyLength >= 0L && bodyLength != returnedLength)
    ) {
      response.close()
      throw IOException(
        if (response.code == 200) {
          "WebDAV server ignored the requested byte range"
        } else {
          "WebDAV ranged request failed with HTTP ${response.code}"
        },
      )
    }

    return RangedResponse(
      response = response,
      start = returnedStart,
      endInclusive = returnedEnd,
      totalLength = totalLength,
    )
  }

  private fun skipExactly(
    stream: InputStream,
    byteCount: Long,
  ) {
    val scratch = ByteArray(SKIP_BUFFER_BYTES)
    var remaining = byteCount
    while (remaining > 0L) {
      val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
      if (read < 0) throw IOException("WebDAV stream ended before the requested offset")
      remaining -= read
    }
  }

  /** Credential-free origin URI. Authenticated playback must use the loopback proxy. */
  override suspend fun getFileUri(path: String): Result<Uri> =
    withContext(Dispatchers.IO) {
      try {
        Result.success(Uri.parse(buildUrl(NetworkPath.from(path).value)))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        Result.failure(error)
      }
    }
}
