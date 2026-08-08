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
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

class WebDavClient(
  private val connection: NetworkConnection,
) : NetworkClient {
  companion object {
    private val rangeHttpClient by lazy { OkHttpClient() }
    private val contentRangePattern = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
  }

  private var sardine: Sardine? = null

  /** Builds a URL from decoded path segments so credentials and reserved characters cannot leak. */
  private fun buildUrl(
    relativePath: String,
    trailingSlash: Boolean = false,
  ): String {
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
    return builder.build().toString()
  }

  override suspend fun connect(): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        val candidate = OkHttpSardine()
        if (!connection.isAnonymous) {
          candidate.setCredentials(connection.username, connection.password)
        }

        if (candidate.list(buildUrl("", trailingSlash = true), 0).isEmpty()) {
          throw IOException("WebDAV base path returned no resources")
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
        val directoryUrl = buildUrl(directory.value, trailingSlash = true)
        val requestedWirePath = java.net.URI(directoryUrl).path.trimEnd('/')
        val resources = client.list(directoryUrl)

        val files =
          resources
            .filterNot { resource -> resource.path.trimEnd('/') == requestedWirePath }
            .mapNotNull { resource: DavResource ->
              val resourceName = resource.name?.trimEnd('/')?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
              runCatching {
                val filePath = directory.child(resourceName)
                NetworkFile(
                  name = resourceName,
                  path = filePath.value,
                  isDirectory = resource.isDirectory,
                  size = resource.contentLength ?: -1L,
                  lastModified = resource.modified?.time ?: 0,
                  mimeType = if (!resource.isDirectory) NetworkMimeTypes.forFileName(resourceName) else null,
                )
              }.getOrNull()
            }

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
        val resources = client.list(buildUrl(NetworkPath.from(path).value), 0)
        val resource = resources.firstOrNull()
        if (resource == null || resource.isDirectory) {
          Result.failure(IOException("File not found or is a directory"))
        } else {
          val size = resource.contentLength
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

  override suspend fun getFileStream(
    path: String,
    offset: Long,
  ): Result<InputStream> =
    withContext(Dispatchers.IO) {
      require(offset >= 0L) { "Stream offset must not be negative" }
      try {
        if (offset > 0L) {
          return@withContext getRangedFileStream(NetworkPath.from(path), offset)
        }

        val streamClient = OkHttpSardine()
        if (!connection.isAnonymous) {
          streamClient.setCredentials(connection.username, connection.password)
        }
        Result.success(streamClient.get(buildUrl(NetworkPath.from(path).value)))
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
    val requestBuilder =
      Request
        .Builder()
        .url(buildUrl(path.value))
        .get()
        .header("Range", "bytes=$offset-")

    if (!connection.isAnonymous) {
      requestBuilder.header("Authorization", Credentials.basic(connection.username, connection.password))
    }

    val response = rangeHttpClient.newCall(requestBuilder.build()).execute()
    val contentRange = response.header("Content-Range")
    val rangeMatch = contentRangePattern.matchEntire(contentRange.orEmpty())
    val returnedStart = rangeMatch?.groupValues?.get(1)?.toLongOrNull()
    val returnedEnd = rangeMatch?.groupValues?.get(2)?.toLongOrNull()

    // A successful HTTP 200 means the server ignored Range. Returning it as if it started at
    // [offset] corrupts seeking, so only a validated 206 response is accepted.
    if (response.code != 206 || returnedStart != offset || returnedEnd == null || returnedEnd < offset) {
      response.close()
      return Result.failure(
        IOException(
          if (response.code == 200) {
            "WebDAV server ignored the requested byte range"
          } else {
            "WebDAV ranged request failed with HTTP ${response.code}"
          },
        ),
      )
    }

    val rawStream = response.body.byteStream()
    return Result.success(
      object : InputStream() {
        override fun read(): Int = rawStream.read()

        override fun read(b: ByteArray): Int = rawStream.read(b)

        override fun read(
          b: ByteArray,
          off: Int,
          len: Int,
        ): Int = rawStream.read(b, off, len)

        override fun available(): Int = rawStream.available()

        override fun close() {
          runCatching { rawStream.close() }
          response.close()
        }
      },
    )
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
