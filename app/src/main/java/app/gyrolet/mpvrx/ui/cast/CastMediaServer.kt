/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.util.UUID

/** Serves one Android-local media item to the selected Cast receiver. */
internal class CastMediaServer private constructor(
  private val appContext: Context,
  private val source: Uri,
  private val mimeType: String,
  private val contentLength: Long,
  private val token: String,
) : NanoHTTPD("0.0.0.0", 0) {
  override fun serve(session: IHTTPSession): Response {
    if (session.uri != "/$token") return textResponse(Response.Status.NOT_FOUND, "Not found")
    if (session.method == Method.OPTIONS) {
      return newFixedLengthResponse(Response.Status.NO_CONTENT, mimeType, "").apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
      }
    }
    if (session.method != Method.GET && session.method != Method.HEAD) {
      return textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
    }

    return runCatching {
      val range = session.headers["range"]
      if (range != null && range.startsWith("bytes=") && contentLength > 0L) {
        serveRange(session, range)
      } else {
        serveFull(session)
      }
    }.getOrElse { error ->
      Log.e(TAG, "Failed to serve Cast media", error)
      textResponse(Response.Status.INTERNAL_ERROR, "Unable to read media")
    }
  }

  private fun serveFull(session: IHTTPSession): Response {
    if (session.method == Method.HEAD) {
      return newFixedLengthResponse(Response.Status.OK, mimeType, "").withMediaHeaders(contentLength)
    }

    val input = openAt(0L) ?: return textResponse(Response.Status.NOT_FOUND, "Media unavailable")
    val response =
      if (contentLength > 0L) {
        newFixedLengthResponse(Response.Status.OK, mimeType, input, contentLength)
      } else {
        newChunkedResponse(Response.Status.OK, mimeType, input)
      }
    return response.withMediaHeaders(contentLength)
  }

  private fun serveRange(
    session: IHTTPSession,
    rangeHeader: String,
  ): Response {
    val value = rangeHeader.removePrefix("bytes=").substringBefore(',')
    val parts = value.split('-', limit = 2)
    val startText = parts.getOrElse(0) { "" }
    val endText = parts.getOrElse(1) { "" }
    val start =
      if (startText.isBlank()) {
        val suffix = endText.toLongOrNull() ?: return rangeNotSatisfiable()
        (contentLength - suffix).coerceAtLeast(0L)
      } else {
        startText.toLongOrNull() ?: return rangeNotSatisfiable()
      }
    val end =
      if (startText.isBlank()) {
        contentLength - 1L
      } else {
        (endText.toLongOrNull() ?: (contentLength - 1L)).coerceAtMost(contentLength - 1L)
      }

    if (start !in 0 until contentLength || end < start) return rangeNotSatisfiable()
    val length = end - start + 1L
    if (session.method == Method.HEAD) {
      return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, "")
        .withRangeHeaders(start, end, length)
    }

    val input = openAt(start) ?: return textResponse(Response.Status.NOT_FOUND, "Media unavailable")
    return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, input, length)
      .withRangeHeaders(start, end, length)
  }

  private fun openAt(offset: Long): InputStream? {
    val descriptor = appContext.contentResolver.openAssetFileDescriptor(source, "r") ?: return null
    val input = descriptor.createInputStream()
    var remaining = offset
    while (remaining > 0L) {
      val skipped = input.skip(remaining)
      if (skipped <= 0L) {
        input.close()
        descriptor.close()
        return null
      }
      remaining -= skipped
    }
    return object : FilterInputStream(input) {
      override fun close() {
        runCatching { super.close() }
        runCatching { descriptor.close() }
      }
    }
  }

  private fun Response.withMediaHeaders(length: Long): Response =
    apply {
      addHeader("Accept-Ranges", "bytes")
      addHeader("Access-Control-Allow-Origin", "*")
      addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
      addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length")
      if (length > 0L) addHeader("Content-Length", length.toString())
    }

  private fun Response.withRangeHeaders(
    start: Long,
    end: Long,
    length: Long,
  ): Response =
    apply {
      withMediaHeaders(length)
      addHeader("Content-Range", "bytes $start-$end/$contentLength")
    }

  private fun rangeNotSatisfiable(): Response =
    textResponse(Response.Status.RANGE_NOT_SATISFIABLE, "Range not satisfiable").apply {
      addHeader("Content-Range", "bytes */$contentLength")
    }

  private fun textResponse(
    status: Response.IStatus,
    message: String,
  ): Response =
    newFixedLengthResponse(status, MIME_PLAINTEXT, message).apply {
      addHeader("Access-Control-Allow-Origin", "*")
    }

  companion object {
    private const val TAG = "CastMediaServer"

    @Volatile private var active: CastMediaServer? = null

    @Synchronized
    fun expose(
      context: Context,
      source: Uri,
      mimeType: String,
    ): String? {
      stop()
      val host = runCatching { findLanAddress(context) }.getOrNull() ?: return null
      val token = UUID.randomUUID().toString().replace("-", "")
      val server =
        CastMediaServer(
          appContext = context.applicationContext,
          source = source,
          mimeType = mimeType,
          contentLength = runCatching { queryLength(context, source) }.getOrDefault(-1L),
          token = token,
        )
      return runCatching {
        server.start(SOCKET_READ_TIMEOUT, false)
        active = server
        "http://$host:${server.listeningPort}/$token"
      }.onFailure { error ->
        Log.e(TAG, "Unable to start Cast media server", error)
        server.stop()
      }.getOrNull()
    }

    @Synchronized
    fun stop() {
      active?.stop()
      active = null
    }

    private fun queryLength(
      context: Context,
      uri: Uri,
    ): Long {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        if (descriptor.length >= 0L) return descriptor.length
      }
      return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else -1L
      } ?: -1L
    }

    private fun findLanAddress(context: Context): String? {
      val connectivity = context.getSystemService(ConnectivityManager::class.java)
      val properties = connectivity.getLinkProperties(connectivity.activeNetwork)
      return properties
        ?.linkAddresses
        ?.asSequence()
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
    }
  }
}
