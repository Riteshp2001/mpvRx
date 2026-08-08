/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.torrent

import android.util.Log
import app.gyrolet.mpvrx.data.network.proxy.HttpByteRange
import fi.iki.elonen.NanoHTTPD
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Loopback-only HTTP byte-range bridge between mpv and a selected torrent file. */
class TorrentProxyServer(
  private val target: Target,
  private val onActivity: () -> Unit = {},
) : NanoHTTPD("127.0.0.1", 0),
  Closeable {
  data class Target(
    val handle: TorrentHandle,
    val file: File,
    val fileOffset: Long,
    val fileSize: Long,
    val pieceLength: Int,
    val firstPiece: Int,
    val lastPiece: Int,
    val mimeType: String,
  )

  companion object {
    private const val TAG = "TorrentProxyServer"
    private const val READ_AHEAD_BYTES = 16L * 1024L * 1024L
    private const val PIECE_WAIT_TIMEOUT_MS = 120_000L
    private const val PIECE_POLL_INTERVAL_MS = 40L
  }

  private class HeadResponse(
    status: Response.IStatus,
    mimeType: String,
    contentLength: Long,
  ) : Response(status, mimeType, ByteArrayInputStream(ByteArray(0)), contentLength)

  private val capability = UUID.randomUUID().toString().replace("-", "")
  private val route = "/stream/$capability"
  private val openStreams = ConcurrentHashMap.newKeySet<TorrentStreamInputStream>()
  private val priorityLock = Any()
  private var prioritizedFrom = -1
  private var prioritizedThrough = -1

  val serverUrl: String
    get() = URI("http", null, "127.0.0.1", listeningPort, route, null, null).toASCIIString()

  override fun serve(session: IHTTPSession): Response {
    onActivity()
    if (session.uri != route) return textResponse(Response.Status.NOT_FOUND, "Stream not found", session.method == Method.HEAD)
    if (session.method != Method.GET && session.method != Method.HEAD) {
      return textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed", false).apply {
        addHeader("Allow", "GET, HEAD")
      }
    }
    if (!target.handle.isValid) return unavailable(session.method == Method.HEAD)

    val headOnly = session.method == Method.HEAD
    val rangeHeader = session.headers["range"]
    if (target.fileSize == 0L) {
      if (rangeHeader != null) return rangeNotSatisfiable(headOnly)
      return emptyResponse(Response.Status.OK, target.mimeType).apply { addHeader("Accept-Ranges", "bytes") }
    }

    val range =
      if (rangeHeader == null) {
        HttpByteRange(0L, target.fileSize - 1L)
      } else {
        HttpByteRange.parse(rangeHeader, target.fileSize) ?: return rangeNotSatisfiable(headOnly)
      }
    val status = if (rangeHeader == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT

    val response =
      if (headOnly) {
        HeadResponse(status, target.mimeType, range.length)
      } else {
        try {
          val stream =
            TorrentStreamInputStream(
              target = target,
              startOffset = range.start,
              length = range.length,
              prioritize = ::prioritize,
              onActivity = onActivity,
              onClosed = { openStreams.remove(it) },
            )
          openStreams += stream
          newFixedLengthResponse(status, target.mimeType, stream, range.length)
        } catch (error: Exception) {
          Log.w(TAG, "Unable to open torrent stream (${error::class.java.simpleName})")
          return unavailable(false)
        }
      }

    response.addHeader("Accept-Ranges", "bytes")
    if (rangeHeader != null) {
      response.addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/${target.fileSize}")
    }
    return response
  }

  private fun prioritize(relativeOffset: Long) {
    val absoluteStart = target.fileOffset + relativeOffset
    val first = (absoluteStart / target.pieceLength).toInt().coerceIn(target.firstPiece, target.lastPiece)
    val absoluteEnd =
      min(
        target.fileOffset + target.fileSize - 1L,
        absoluteStart + READ_AHEAD_BYTES - 1L,
      )
    val last = (absoluteEnd / target.pieceLength).toInt().coerceIn(first, target.lastPiece)

    synchronized(priorityLock) {
      if (first == prioritizedFrom && last <= prioritizedThrough) return
      target.handle.setSequentialRange(first, target.lastPiece)
      for (piece in first..last) {
        target.handle.piecePriority(piece, Priority.TOP_PRIORITY)
        target.handle.setPieceDeadline(piece, ((piece - first) * 100).coerceAtMost(10_000))
      }
      prioritizedFrom = first
      prioritizedThrough = last
    }
  }

  override fun close() {
    super.stop()
    openStreams.toList().forEach { runCatching { it.close() } }
    openStreams.clear()
  }

  private fun rangeNotSatisfiable(headOnly: Boolean): Response =
    textResponse(Response.Status.RANGE_NOT_SATISFIABLE, "Requested range not satisfiable", headOnly).apply {
      addHeader("Content-Range", "bytes */${target.fileSize}")
      addHeader("Accept-Ranges", "bytes")
    }

  private fun unavailable(headOnly: Boolean): Response =
    textResponse(Response.Status.SERVICE_UNAVAILABLE, "Torrent data is unavailable", headOnly)

  private fun emptyResponse(
    status: Response.IStatus,
    mimeType: String,
  ): Response = newFixedLengthResponse(status, mimeType, ByteArrayInputStream(ByteArray(0)), 0L)

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

  private class TorrentStreamInputStream(
    private val target: Target,
    private val startOffset: Long,
    private val length: Long,
    private val prioritize: (Long) -> Unit,
    private val onActivity: () -> Unit,
    private val onClosed: (TorrentStreamInputStream) -> Unit,
  ) : InputStream() {
    private var input: RandomAccessFile? = null
    private var position = 0L
    private var closed = false

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(
      buffer: ByteArray,
      offset: Int,
      byteCount: Int,
    ): Int {
      if (closed || position >= length) return -1
      if (byteCount == 0) return 0
      if (
        offset < 0 || byteCount < 0 || offset > buffer.size || byteCount > buffer.size - offset
      ) {
        throw IndexOutOfBoundsException()
      }

      val requested = min(byteCount.toLong(), length - position).toInt()
      val relativePosition = startOffset + position
      prioritize(relativePosition)
      waitForPieces(relativePosition, requested)

      val file = input ?: openFile(relativePosition).also { input = it }
      if (file.filePointer != relativePosition) file.seek(relativePosition)
      val read = file.read(buffer, offset, requested)
      if (read < 0) throw IOException("Verified torrent data was not available on disk")
      position += read
      onActivity()
      return read
    }

    private fun openFile(relativePosition: Long): RandomAccessFile {
      val deadline = System.currentTimeMillis() + 5_000L
      while (!target.file.isFile && System.currentTimeMillis() < deadline) {
        ensureActive()
        Thread.sleep(PIECE_POLL_INTERVAL_MS)
      }
      if (!target.file.isFile) throw IOException("Torrent file is not available")
      return RandomAccessFile(target.file, "r").apply { seek(relativePosition) }
    }

    private fun waitForPieces(
      relativePosition: Long,
      byteCount: Int,
    ) {
      val absoluteStart = target.fileOffset + relativePosition
      val absoluteEnd = absoluteStart + byteCount - 1L
      val first = (absoluteStart / target.pieceLength).toInt()
      val last = (absoluteEnd / target.pieceLength).toInt()
      val deadline = System.currentTimeMillis() + PIECE_WAIT_TIMEOUT_MS

      for (piece in first..last) {
        while (!target.handle.havePiece(piece)) {
          ensureActive()
          if (System.currentTimeMillis() >= deadline) throw IOException("Timed out waiting for torrent data")
          onActivity()
          try {
            Thread.sleep(PIECE_POLL_INTERVAL_MS)
          } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Torrent stream interrupted", interrupted)
          }
        }
      }
    }

    private fun ensureActive() {
      if (closed || !target.handle.isValid) throw IOException("Torrent stream stopped")
    }

    override fun close() {
      if (closed) return
      closed = true
      runCatching { input?.close() }
      input = null
      onClosed(this)
    }
  }
}
