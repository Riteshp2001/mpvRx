/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.archive

import android.media.MediaDataSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Serves a single ZIP entry to Android's media stack ([android.media.MediaMetadataRetriever],
 * `MediaExtractor`) straight out of the archive, so reading an entry's metadata or a frame never
 * writes the entry anywhere and never duplicates the archive.
 *
 * Random access is the awkward part: a DEFLATED entry is a decompression stream that cannot be
 * seeked. So instead of holding the entry, this holds one fixed [WINDOW_BYTES] buffer and:
 *
 * - reads that move forward simply continue the stream — skipping decompresses and discards,
 * - reads that move backwards reopen the stream and skip ahead again.
 *
 * For STORED entries, and for the incompressible audio/video data that dominates real archives,
 * "decompressing" is essentially a memory copy, so the cost of a pass is about the cost of reading
 * the file once. Highly compressible entries are cheaper still. Either way memory stays at one
 * window regardless of the entry size, and nothing is written to storage.
 *
 * Every method is synchronized: the native media stack issues reads from several threads.
 */
internal class ZipEntryMediaDataSource(
  archiveFile: File,
  entryPath: String,
) : MediaDataSource() {
  private val archive = ZipFile(archiveFile)
  private val entry: ZipEntry =
    ZipArchiveMedia.findEntry(archive, entryPath) ?: throw IOException("ZIP entry not found: $entryPath")
  private val size = entry.size

  private val window = ByteArray(WINDOW_BYTES)
  private val skipBuffer = ByteArray(SKIP_BUFFER_BYTES)
  private var windowStart = 0L
  private var windowLength = 0
  private var stream: InputStream? = null
  private var streamPosition = 0L
  private var closed = false

  init {
    if (entry.isDirectory) throw IOException("ZIP entry is a directory: $entryPath")
    if (size < 0L) throw IOException("ZIP entry has no uncompressed size: $entryPath")
  }

  override fun getSize(): Long = size

  @Synchronized
  override fun readAt(
    position: Long,
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    if (closed) throw IOException("ZIP entry data source is closed")
    require(position >= 0L && offset >= 0 && length >= 0 && offset + length <= buffer.size) {
      "Invalid read of $length bytes at $position"
    }
    if (length == 0) return 0
    if (position >= size) return -1

    val requested = minOf(length.toLong(), size - position).toInt()
    var copied = 0
    while (copied < requested) {
      val absolute = position + copied
      if (!positionWindow(absolute)) break
      val offsetInWindow = (absolute - windowStart).toInt()
      val available = minOf(windowLength - offsetInWindow, requested - copied)
      if (available <= 0) break
      System.arraycopy(window, offsetInWindow, buffer, offset + copied, available)
      copied += available
    }
    return if (copied == 0) -1 else copied
  }

  @Synchronized
  override fun close() {
    if (closed) return
    closed = true
    runCatching { stream?.close() }
    stream = null
    runCatching { archive.close() }
    windowStart = 0L
    windowLength = 0
  }

  /** Brings [position] into the window, reopening the entry stream when it has to go backwards. */
  private fun positionWindow(position: Long): Boolean {
    if (position >= windowStart && position < windowStart + windowLength) return true
    if (!moveStreamTo(position)) return false

    windowStart = position
    windowLength = 0
    val input = stream ?: return false
    var total = 0
    while (total < window.size) {
      val count = input.read(window, total, window.size - total)
      if (count <= 0) break
      total += count
    }
    windowLength = total
    streamPosition = position + total
    return total > 0
  }

  /** Leaves the entry stream positioned at [position], reopening it when it cannot skip forward. */
  private fun moveStreamTo(position: Long): Boolean {
    val current = stream ?: return openStreamAt(position)
    if (position < streamPosition) return openStreamAt(position)

    var remaining = position - streamPosition
    while (remaining > 0L) {
      val skipped = runCatching { current.skip(remaining) }.getOrDefault(0L)
      if (skipped > 0L) {
        remaining -= skipped
        continue
      }
      val count =
        runCatching { current.read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt()) }
          .getOrDefault(-1)
      if (count <= 0) return false
      remaining -= count
    }
    streamPosition = position
    return true
  }

  private fun openStreamAt(position: Long): Boolean {
    runCatching { stream?.close() }
    stream = null
    streamPosition = 0L
    val fresh = runCatching { archive.getInputStream(entry) }.getOrNull() ?: return false
    stream = fresh
    return moveStreamTo(position)
  }

  private companion object {
    const val WINDOW_BYTES = 1 shl 20
    const val SKIP_BUFFER_BYTES = 64 shl 10
  }
}
