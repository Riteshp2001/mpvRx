/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.RandomAccessFile
import kotlin.math.min

internal object MatroskaEmbeddedArtworkExtractor {
  private const val ID_SEGMENT = 0x18538067L
  private const val ID_ATTACHMENTS = 0x1941A469L
  private const val ID_ATTACHED_FILE = 0x61A7L
  private const val ID_FILE_NAME = 0x466EL
  private const val ID_FILE_MIME_TYPE = 0x4660L
  private const val ID_FILE_DATA = 0x465CL
  private const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
  private const val MAX_DEPTH = 4

  fun decode(path: String?): Bitmap? {
    val localPath = path?.toLocalFilePath()?.takeIf { it.hasMatroskaExtension() } ?: return null
    return runCatching {
      RandomAccessFile(localPath, "r").use { file ->
        findArtwork(file, start = 0L, end = file.length(), depth = 0)?.decode(file)
      }
    }.getOrNull()
  }

  private fun findArtwork(
    file: RandomAccessFile,
    start: Long,
    end: Long,
    depth: Int,
  ): Attachment? {
    if (depth > MAX_DEPTH) return null

    var position = start
    while (position < end - 2) {
      file.seek(position)
      val header = readElementHeader(file) ?: return null
      val dataEnd = header.dataEnd(end)
      if (dataEnd < header.dataOffset || dataEnd > end) return null

      when (header.id) {
        ID_SEGMENT, ID_ATTACHMENTS ->
          findArtwork(file, header.dataOffset, dataEnd, depth + 1)?.let { return it }
        ID_ATTACHED_FILE ->
          parseAttachedFile(file, header.dataOffset, dataEnd)?.let { return it }
      }

      position = dataEnd
    }

    return null
  }

  private fun parseAttachedFile(
    file: RandomAccessFile,
    start: Long,
    end: Long,
  ): Attachment? {
    var position = start
    var name: String? = null
    var mimeType: String? = null
    var dataOffset = -1L
    var dataSize = -1L

    while (position < end - 2) {
      file.seek(position)
      val header = readElementHeader(file) ?: return null
      val dataEnd = header.dataEnd(end)
      if (dataEnd < header.dataOffset || dataEnd > end) return null

      when (header.id) {
        ID_FILE_NAME -> name = readString(file, header.dataOffset, header.size)
        ID_FILE_MIME_TYPE -> mimeType = readString(file, header.dataOffset, header.size)
        ID_FILE_DATA -> {
          dataOffset = header.dataOffset
          dataSize = header.size
        }
      }

      position = dataEnd
    }

    return Attachment(
      name = name.orEmpty(),
      mimeType = mimeType.orEmpty(),
      dataOffset = dataOffset,
      dataSize = dataSize,
    ).takeIf { it.isImage && it.dataSize in 1..MAX_ATTACHMENT_BYTES }
  }

  private fun Attachment.decode(file: RandomAccessFile): Bitmap? {
    if (dataOffset < 0 || dataSize <= 0 || dataSize > MAX_ATTACHMENT_BYTES) return null
    val bytes = ByteArray(dataSize.toInt())
    file.seek(dataOffset)
    file.readFully(bytes)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val sampleSize = calculateThumbnailSampleSize(options.outWidth, options.outHeight)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.RGB_565
    })
  }

  private fun readElementHeader(file: RandomAccessFile): ElementHeader? {
    val start = file.filePointer
    val id = readEbmlId(file) ?: return null
    val size = readEbmlSize(file) ?: return null
    return ElementHeader(
      id = id,
      size = size,
      dataOffset = file.filePointer,
      headerSize = file.filePointer - start,
    )
  }

  private fun readEbmlId(file: RandomAccessFile): Long? {
    val first = file.read()
    if (first < 0) return null
    val length = vintLength(first).takeIf { it in 1..4 } ?: return null
    var value = first.toLong()
    repeat(length - 1) {
      val next = file.read()
      if (next < 0) return null
      value = (value shl 8) or next.toLong()
    }
    return value
  }

  private fun readEbmlSize(file: RandomAccessFile): Long? {
    val first = file.read()
    if (first < 0) return null
    val length = vintLength(first).takeIf { it in 1..8 } ?: return null
    val mask = (1 shl (8 - length)) - 1
    var value = (first and mask).toLong()
    repeat(length - 1) {
      val next = file.read()
      if (next < 0) return null
      value = (value shl 8) or next.toLong()
    }
    val unknownValue = (1L shl (7 * length)) - 1L
    return if (value == unknownValue) -1L else value
  }

  private fun vintLength(firstByte: Int): Int {
    var mask = 0x80
    for (length in 1..8) {
      if ((firstByte and mask) != 0) return length
      mask = mask ushr 1
    }
    return -1
  }

  private fun readString(
    file: RandomAccessFile,
    offset: Long,
    size: Long,
  ): String? {
    if (size !in 1..4096) return null
    val bytes = ByteArray(size.toInt())
    file.seek(offset)
    file.readFully(bytes)
    return bytes.toString(Charsets.UTF_8).trim('\u0000', ' ', '\t', '\n', '\r')
  }

  private fun String.toLocalFilePath(): String? =
    when {
      startsWith("file://", ignoreCase = true) -> removePrefix("file://")
      contains("://") -> null
      else -> this
    }?.replace("%20", " ")

  private fun String.hasMatroskaExtension(): Boolean {
    val ext = substringBefore("?").substringAfterLast('.', "").lowercase()
    return ext in setOf("mkv", "mka", "mks", "mk3d", "webm")
  }

  private val Attachment.isImage: Boolean
    get() {
      val normalizedMime = mimeType.lowercase()
      if (normalizedMime.startsWith("image/")) return true
      val extension = name.substringAfterLast('.', "").lowercase()
      return extension in setOf("jpg", "jpeg", "png", "webp")
    }

  private fun ElementHeader.dataEnd(parentEnd: Long): Long =
    if (size < 0) {
      parentEnd
    } else {
      min(parentEnd, dataOffset + size)
    }

  private data class ElementHeader(
    val id: Long,
    val size: Long,
    val dataOffset: Long,
    val headerSize: Long,
  )

  private data class Attachment(
    val name: String,
    val mimeType: String,
    val dataOffset: Long,
    val dataSize: Long,
  )
}
