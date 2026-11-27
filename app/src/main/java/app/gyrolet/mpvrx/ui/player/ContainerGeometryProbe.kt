/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

/** Video track geometry read from a container header: display size before rotation, plus rotation. */
internal data class ContainerGeometry(
  val width: Int,
  val height: Int,
  val quarterTurned: Boolean,
  val probeNanos: Long,
)

/**
 * Reads a video's geometry straight from its container header so the player opens the right way
 * round before mpv reports anything.
 *
 * MediaStore records video rotation only from Android 11, and often stores no size at all for
 * Matroska. Probing runs on a background thread started when a video is tapped; the player then
 * only waits a bounded time for the result, so a cold SD card can delay the decision but never
 * produces a wrong one. Only box/element headers are read: never sample tables or media data.
 */
internal object ContainerGeometryProbe {
  private const val CACHE_LIMIT = 64
  private const val MAX_BOX_VISITS = 512
  private const val PROBE_TIME_LIMIT_NANOS = 2_000_000_000L
  private const val MATROSKA_PROBE_BYTES = 256 * 1024
  private const val FIXED_ONE = 0x10000

  private val executor: ExecutorService =
    Executors.newFixedThreadPool(2) { runnable ->
      Thread(runnable, "mpvRx-geometry-probe").apply { isDaemon = true }
    }
  private val probes =
    object : LinkedHashMap<String, Future<ContainerGeometry?>>(CACHE_LIMIT, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Future<ContainerGeometry?>>?): Boolean =
        size > CACHE_LIMIT
    }

  /** Whether launch metadata alone cannot place the video: no size, or no rotation index (pre-Android 11). */
  fun needsProbe(
    width: Int,
    height: Int,
    rotation: Int,
  ): Boolean = width <= 0 || height <= 0 || (rotation == 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.R)

  /** Starts probing [path] in the background; call when a video is tapped, before starting the player. */
  fun prefetch(
    path: String?,
    width: Int,
    height: Int,
    rotation: Int,
    isAudio: Boolean,
  ) {
    if (isAudio || path.isNullOrBlank() || !needsProbe(width, height, rotation)) return
    submit(path) { readFile(path) }
  }

  /** The probe for a local [path] or `content:` [uri], starting it if no prefetch did. */
  fun request(
    path: String?,
    uri: Uri?,
    contentResolver: ContentResolver,
  ): Future<ContainerGeometry?>? =
    when {
      !path.isNullOrBlank() -> submit(path) { readFile(path) }
      uri != null && uri.scheme == ContentResolver.SCHEME_CONTENT ->
        submit(uri.toString()) { readContent(contentResolver, uri) }
      else -> null
    }

  /** Whether a probe for [path] was already started, e.g. by [prefetch] when the video was tapped. */
  fun isPrefetched(path: String?): Boolean = !path.isNullOrBlank() && synchronized(probes) { probes.containsKey(path) }

  private fun submit(
    key: String,
    task: () -> ContainerGeometry?,
  ): Future<ContainerGeometry?> =
    synchronized(probes) {
      // A failed read (e.g. storage still mounting) is retried rather than remembered.
      probes[key]?.takeUnless { it.isDone && runCatching { it.get() }.getOrNull() == null }
        ?: executor.submit(Callable { task() }).also { probes[key] = it }
    }

  private fun readFile(path: String): ContainerGeometry? =
    runCatching { RandomAccessFile(path, "r").use { file -> read(file.channel) } }.getOrNull()

  private fun readContent(
    contentResolver: ContentResolver,
    uri: Uri,
  ): ContainerGeometry? =
    runCatching {
      contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { stream -> read(stream.channel) }
      }
    }.getOrNull()

  private fun read(channel: FileChannel): ContainerGeometry? {
    val started = System.nanoTime()
    val magic = channel.readAt(0L, 8) ?: return null
    val geometry =
      when {
        magic.getInt(0) == 0x1A45DFA3 -> readMatroska(channel)
        String(ByteArray(4) { magic.get(4 + it) }, Charsets.US_ASCII) in isoBmffTopLevelBoxes ->
          IsoBmffReader(channel, started + PROBE_TIME_LIMIT_NANOS).videoTrackGeometry()
        else -> null
      }
    return geometry?.copy(probeNanos = System.nanoTime() - started)
  }

  private val isoBmffTopLevelBoxes = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")

  private class Box(
    val type: String,
    val payloadStart: Long,
    val end: Long,
  )

  private class IsoBmffReader(
    private val channel: FileChannel,
    private val deadline: Long,
  ) {
    private var visits = 0

    fun videoTrackGeometry(): ContainerGeometry? {
      val moov = children(0L, channel.size()).firstOrNull { it.type == "moov" } ?: return null
      for (trak in children(moov.payloadStart, moov.end).filter { it.type == "trak" }) {
        var trackHeader: ContainerGeometry? = null
        var isVideo = false
        for (box in children(trak.payloadStart, trak.end)) {
          when (box.type) {
            "tkhd" -> trackHeader = readTrackHeader(box)
            "mdia" -> isVideo = isVideoMedia(box)
          }
        }
        if (isVideo) return trackHeader
      }
      return null
    }

    private fun children(
      start: Long,
      end: Long,
    ): Sequence<Box> =
      sequence {
        var position = start
        while (position + 8 <= end) {
          if (++visits > MAX_BOX_VISITS || System.nanoTime() > deadline) return@sequence
          val header = channel.readAt(position, 8) ?: return@sequence
          val size32 = header.getInt(0).toLong() and 0xFFFFFFFFL
          val type = String(ByteArray(4) { header.get(4 + it) }, Charsets.US_ASCII)
          val (size, headerSize) =
            when (size32) {
              0L -> (end - position) to 8L
              1L -> (channel.readAt(position + 8, 8)?.getLong(0) ?: return@sequence) to 16L
              else -> size32 to 8L
            }
          if (size < headerSize || position + size > end) return@sequence
          yield(Box(type, position + headerSize, position + size))
          position += size
        }
      }

    private fun isVideoMedia(mdia: Box): Boolean {
      val hdlr = children(mdia.payloadStart, mdia.end).firstOrNull { it.type == "hdlr" } ?: return false
      if (hdlr.end - hdlr.payloadStart < 12) return false
      val payload = channel.readAt(hdlr.payloadStart, 12) ?: return false
      // version/flags (4) and pre_defined (4) precede the handler type.
      return String(ByteArray(4) { payload.get(8 + it) }, Charsets.US_ASCII) == "vide"
    }

    private fun readTrackHeader(tkhd: Box): ContainerGeometry? {
      val available = (tkhd.end - tkhd.payloadStart).coerceAtMost(96L).toInt()
      val payload = channel.readAt(tkhd.payloadStart, available) ?: return null
      // version/flags (4), times/ids/duration (version 1: 32, version 0: 20), reserved/layer/group/volume (16).
      val matrixOffset = 4 + (if (payload.get(0).toInt() == 1) 32 else 20) + 16
      if (available < matrixOffset + 44) return null
      val a = payload.getInt(matrixOffset)
      val b = payload.getInt(matrixOffset + 4)
      val c = payload.getInt(matrixOffset + 12)
      val d = payload.getInt(matrixOffset + 16)
      val near = FIXED_ONE / 256
      val quarterTurned =
        abs(a) < near && abs(d) < near && abs(abs(b) - FIXED_ONE) < near && abs(abs(c) - FIXED_ONE) < near
      // Track width and height are unsigned 16.16 fixed point; the integer part is the display size.
      val width = payload.getInt(matrixOffset + 36) ushr 16
      val height = payload.getInt(matrixOffset + 40) ushr 16
      return ContainerGeometry(width, height, quarterTurned, 0L)
    }
  }

  private fun readMatroska(channel: FileChannel): ContainerGeometry? {
    val length = channel.size().coerceAtMost(MATROSKA_PROBE_BYTES.toLong()).toInt()
    val buffer = ByteBuffer.allocate(length)
    var offset = 0L
    while (buffer.hasRemaining()) {
      val read = channel.read(buffer, offset)
      if (read <= 0) break
      offset += read
    }
    return MatroskaReader(buffer.array(), buffer.position()).videoTrackGeometry()
  }

  private class MatroskaReader(
    private val data: ByteArray,
    private val limit: Int,
  ) {
    private class Element(
      val id: Long,
      val dataStart: Int,
      val dataEnd: Int,
      val unknownSize: Boolean,
    )

    fun videoTrackGeometry(): ContainerGeometry? {
      val ebmlHeader = element(0) ?: return null
      val segment = element(ebmlHeader.dataEnd)?.takeIf { it.id == SEGMENT } ?: return null
      for (child in children(segment.dataStart, segment.dataEnd)) {
        when (child.id) {
          TRACKS -> return tracks(child)
          CLUSTER -> return null
        }
        if (child.unknownSize) return null
      }
      return null
    }

    private fun tracks(tracks: Element): ContainerGeometry? {
      for (entry in children(tracks.dataStart, tracks.dataEnd).filter { it.id == TRACK_ENTRY }) {
        var isVideo = false
        var video: Element? = null
        for (field in children(entry.dataStart, entry.dataEnd)) {
          when (field.id) {
            TRACK_TYPE -> isVideo = unsigned(field) == 1L
            VIDEO -> video = field
          }
        }
        val videoSettings = video ?: continue
        if (!isVideo) continue
        var pixelWidth = 0L
        var pixelHeight = 0L
        var displayWidth = 0L
        var displayHeight = 0L
        for (setting in children(videoSettings.dataStart, videoSettings.dataEnd)) {
          when (setting.id) {
            PIXEL_WIDTH -> pixelWidth = unsigned(setting)
            PIXEL_HEIGHT -> pixelHeight = unsigned(setting)
            DISPLAY_WIDTH -> displayWidth = unsigned(setting)
            DISPLAY_HEIGHT -> displayHeight = unsigned(setting)
          }
        }
        val width = (if (displayWidth > 0 && displayHeight > 0) displayWidth else pixelWidth).toInt()
        val height = (if (displayWidth > 0 && displayHeight > 0) displayHeight else pixelHeight).toInt()
        return ContainerGeometry(width, height, quarterTurned = false, probeNanos = 0L)
      }
      return null
    }

    private fun children(
      start: Int,
      end: Int,
    ): Sequence<Element> =
      sequence {
        var position = start
        while (position < end) {
          val child = element(position) ?: return@sequence
          yield(child)
          if (child.unknownSize) return@sequence
          position = child.dataEnd
        }
      }

    private fun element(position: Int): Element? {
      val idLength = vintLength(position, maxLength = 4) ?: return null
      var id = 0L
      for (index in 0 until idLength) id = (id shl 8) or (data[position + index].toLong() and 0xFF)
      val sizeStart = position + idLength
      val sizeLength = vintLength(sizeStart, maxLength = 8) ?: return null
      var size = (data[sizeStart].toLong() and 0xFF) and (0xFFL ushr sizeLength)
      var allOnes = size == (0xFFL ushr sizeLength)
      for (index in 1 until sizeLength) {
        val byte = data[sizeStart + index].toLong() and 0xFF
        size = (size shl 8) or byte
        allOnes = allOnes && byte == 0xFFL
      }
      val dataStart = sizeStart + sizeLength
      if (dataStart > limit) return null
      val dataEnd = if (allOnes) limit else (dataStart + size).coerceAtMost(limit.toLong()).toInt()
      return Element(id, dataStart, dataEnd, unknownSize = allOnes)
    }

    private fun vintLength(
      position: Int,
      maxLength: Int,
    ): Int? {
      if (position >= limit) return null
      val first = data[position].toInt() and 0xFF
      if (first == 0) return null
      val length = Integer.numberOfLeadingZeros(first) - 23
      return length.takeIf { it <= maxLength && position + it <= limit }
    }

    private fun unsigned(element: Element): Long {
      var value = 0L
      for (index in element.dataStart until element.dataEnd.coerceAtMost(element.dataStart + 8)) {
        value = (value shl 8) or (data[index].toLong() and 0xFF)
      }
      return value
    }

    private companion object {
      const val SEGMENT = 0x18538067L
      const val TRACKS = 0x1654AE6BL
      const val CLUSTER = 0x1F43B675L
      const val TRACK_ENTRY = 0xAEL
      const val TRACK_TYPE = 0x83L
      const val VIDEO = 0xE0L
      const val PIXEL_WIDTH = 0xB0L
      const val PIXEL_HEIGHT = 0xBAL
      const val DISPLAY_WIDTH = 0x54B0L
      const val DISPLAY_HEIGHT = 0x54BAL
    }
  }

  /** Milliseconds since [startNanos] on the [SystemClock.elapsedRealtimeNanos] timebase. */
  fun elapsedMs(startNanos: Long): Double = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
}

/** Exactly [length] bytes at [position], or null at end of file. */
private fun FileChannel.readAt(
  position: Long,
  length: Int,
): ByteBuffer? {
  val buffer = ByteBuffer.allocate(length)
  var offset = position
  while (buffer.hasRemaining()) {
    val read = read(buffer, offset)
    if (read <= 0) return null
    offset += read
  }
  buffer.flip()
  return buffer
}
