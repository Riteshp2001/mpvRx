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
import app.gyrolet.mpvrx.domain.network.DlnaPath
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.network.SharedHttpClient
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** UPnP ContentDirectory browsing with durable object IDs and ranged HTTP playback. */
class DlnaClient(private val connection: NetworkConnection) : NetworkClient {
  companion object {
    private const val PAGE_SIZE = 128
    private const val MAX_ITEMS = 50_000
    private val soapType = "text/xml; charset=utf-8".toMediaType()
    private val contentRange = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    private val http by lazy { SharedHttpClient.derive { callTimeout(0, TimeUnit.SECONDS) } }
  }

  @Volatile private var device: DlnaDevice? = null
  private val metadata = ConcurrentHashMap<String, DlnaEntry>()

  private fun descriptionUrl(): HttpUrl {
    val builder = HttpUrl.Builder()
      .scheme(if (connection.useHttps) "https" else "http")
      .host(connection.host.trim().removePrefix("[").removeSuffix("]"))
      .port(connection.port)
    NetworkPath.from(connection.path).segments.forEach(builder::addPathSegment)
    return builder.build()
  }

  override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val location = descriptionUrl()
      val request = Request.Builder().url(location).get().build()
      val found = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("DLNA description failed with HTTP ${response.code}")
        DlnaXml.description(DlnaXml.readBounded(response.body.byteStream()), response.request.url)
      }
      currentCoroutineContext().ensureActive()
      device = found
      Result.success(Unit)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      device = null
      Result.failure(error)
    }
  }

  override suspend fun disconnect() {
    device = null
    metadata.clear()
  }

  override fun isConnected(): Boolean = device != null

  private fun browse(id: String, metadataOnly: Boolean, start: Int, count: Int): DlnaBrowsePage {
    val server = device ?: throw IOException("DLNA server is not connected")
    val body = DlnaXml.browseRequest(server.serviceType, id, metadataOnly, start, count).toRequestBody(soapType)
    val request = Request.Builder().url(server.controlUrl)
      .header("SOAPAction", "\"${server.serviceType}#Browse\"")
      .post(body).build()
    return http.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("DLNA browse failed with HTTP ${response.code}")
      DlnaXml.browseResponse(DlnaXml.readBounded(response.body.byteStream()), server.baseUrl)
    }
  }

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> = withContext(Dispatchers.IO) {
    try {
      val directory = NetworkPath.from(path)
      val id = DlnaPath.objectId(directory)
      val files = ArrayList<NetworkFile>()
      val occurrences = mutableMapOf<Pair<Char, String>, Int>()
      var start = 0
      while (true) {
        currentCoroutineContext().ensureActive()
        val page = browse(id, false, start, PAGE_SIZE)
        if (page.returned == 0) break
        if (page.entries.isEmpty()) {
          // A page can contain unsupported UPnP objects. Advance using NumberReturned.
          start += page.returned
        } else {
          page.entries.forEach { entry ->
            val key = entry.kind to entry.id
            val duplicate = occurrences.getOrDefault(key, 0)
            occurrences[key] = duplicate + 1
            val child = DlnaPath.child(directory, entry.id, entry.kind, duplicate)
            files += NetworkFile(entry.title, child.value, entry.size, entry.kind == 'd' || entry.kind == 'o', mimeType = entry.mimeType)
          }
          start += page.returned
        }
        if (start > MAX_ITEMS) throw IOException("DLNA folder has too many items")
        if (page.total != null && start >= page.total) break
        if (page.total == null && page.returned < PAGE_SIZE) break
      }
      Result.success(files)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  private fun resolve(path: String): DlnaEntry {
    val id = DlnaPath.objectId(NetworkPath.from(path))
    metadata[id]?.let { return it }
    val entry = browse(id, true, 0, 1).entries.firstOrNull { it.resource != null }
      ?: throw IOException("DLNA media is no longer available")
    metadata[id] = entry
    return entry
  }

  override suspend fun getFileUri(path: String): Result<Uri> = withContext(Dispatchers.IO) {
    try {
      Result.success(Uri.parse(requireNotNull(resolve(path).resource).toString()))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  override suspend fun getFileSize(path: String): Result<Long> = withContext(Dispatchers.IO) {
    try {
      val entry = resolve(path)
      if (entry.size >= 0) return@withContext Result.success(entry.size)
      val url = requireNotNull(entry.resource)
      val head = Request.Builder().url(url).head().header("Accept-Encoding", "identity").build()
      val size = http.newCall(head).execute().use { response ->
        if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 } else null
      } ?: run {
        val request = Request.Builder().url(url).get().header("Range", "bytes=0-0")
          .header("Accept-Encoding", "identity").build()
        http.newCall(request).execute().use { response ->
          val range = contentRange.matchEntire(response.header("Content-Range").orEmpty())
          range?.groupValues?.get(3)?.toLongOrNull()
            ?: if (response.code == 200) response.header("Content-Length")?.toLongOrNull() else null
        }
      }
      Result.success(size?.takeIf { it >= 0 } ?: throw IOException("DLNA server did not provide a file size"))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  private data class RangedResponse(val response: Response, val start: Long, val end: Long?, val total: Long?)

  private fun open(url: HttpUrl, offset: Long): RangedResponse {
    val request = Request.Builder().url(url).get().header("Range", "bytes=$offset-")
      .header("Accept-Encoding", "identity").build()
    val response = http.newCall(request).execute()
    try {
      val length = response.body.contentLength()
      if (response.code == 200) {
        if (length >= 0 && offset > length) throw IOException("DLNA stream offset is out of range")
        val input = response.body.byteStream()
        val buffer = ByteArray(64 * 1024)
        var remaining = offset
        while (remaining > 0) {
          val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
          if (count < 0) throw IOException("DLNA stream ended before the requested offset")
          remaining -= count
        }
        return RangedResponse(response, offset, length.takeIf { it >= 0 }?.minus(1), length.takeIf { it >= 0 })
      }
      val match = contentRange.matchEntire(response.header("Content-Range").orEmpty())
      val start = match?.groupValues?.get(1)?.toLongOrNull()
      val end = match?.groupValues?.get(2)?.toLongOrNull()
      val total = match?.groupValues?.get(3)?.toLongOrNull()
      if (response.code != 206 || start != offset || end == null || end < offset ||
        (length >= 0 && length != end - offset + 1) || (total != null && end >= total)) {
        throw IOException("DLNA server returned an invalid byte range (HTTP ${response.code})")
      }
      return RangedResponse(response, offset, end, total)
    } catch (error: Exception) {
      response.close()
      throw error
    }
  }

  override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> = withContext(Dispatchers.IO) {
    try {
      require(offset >= 0) { "Stream offset must not be negative" }
      val url = requireNotNull(resolve(path).resource)
      val first = open(url, offset)
      Result.success(object : InputStream() {
        private var current = first
        private var stream = first.response.body.byteStream()
        private var position = first.start
        private var remaining = first.end?.let { it - first.start + 1 }
        private var total = first.total
        private var closed = false

        override fun read(): Int {
          val byte = ByteArray(1)
          return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
          if (len == 0) return 0
          check(!closed) { "DLNA stream is closed" }
          while (true) {
            if (remaining == 0L) {
              val length = total ?: return -1
              if (position >= length) return -1
              current.response.close()
              val next = open(url, position)
              if (next.total != null && next.total != length) {
                next.response.close()
                throw IOException("DLNA resource length changed during playback")
              }
              current = next
              stream = next.response.body.byteStream()
              remaining = next.end?.let { it - next.start + 1 }
              total = next.total ?: length
            }
            val count = stream.read(b, off, remaining?.let { minOf(it, len.toLong()).toInt() } ?: len)
            if (count > 0) {
              position += count
              remaining = remaining?.minus(count)
              return count
            }
            if (count == 0) continue
            if (remaining == null) return -1
            throw IOException("DLNA range ended prematurely")
          }
        }

        override fun close() {
          if (!closed) { closed = true; current.response.close() }
        }
      })
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Result.failure(error)
    }
  }
}
