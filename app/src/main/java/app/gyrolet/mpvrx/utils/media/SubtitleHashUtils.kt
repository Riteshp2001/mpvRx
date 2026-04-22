package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenSubtitles-compatible file hash used for verified subtitle matching.
 *
 * The final hash is:
 * file_size + checksum(first 64 KB) + checksum(last 64 KB)
 */
object SubtitleHashUtils {
  private const val TAG = "SubtitleHashUtils"
  private const val HASH_CHUNK_SIZE = 65_536L

  fun computeHash(
    context: Context,
    uri: Uri,
  ): String? =
    try {
      val fileSize = getFileSize(context, uri)
      if (fileSize < HASH_CHUNK_SIZE) {
        return null
      }

      val headBuffer = readChunk(context, uri, 0L) ?: return null
      val tailBuffer = readChunk(context, uri, maxOf(0L, fileSize - HASH_CHUNK_SIZE)) ?: return null

      val finalHash = fileSize + computeChunkHash(headBuffer) + computeChunkHash(tailBuffer)
      String.format("%016x", finalHash)
    } catch (error: Exception) {
      Log.e(TAG, "Error computing hash for $uri", error)
      null
    }

  private fun getFileSize(
    context: Context,
    uri: Uri,
  ): Long =
    when (uri.scheme) {
      "file" -> uri.path?.let { File(it).length() } ?: 0L
      else ->
        runCatching {
          context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize
          } ?: 0L
        }.getOrDefault(0L)
    }

  private fun readChunk(
    context: Context,
    uri: Uri,
    offset: Long,
  ): ByteArray? {
    val buffer = ByteArray(HASH_CHUNK_SIZE.toInt())

    openInputStream(context, uri)?.use { inputStream ->
      if (!skipFully(inputStream, offset)) {
        return null
      }

      var totalRead = 0
      while (totalRead < buffer.size) {
        val read = inputStream.read(buffer, totalRead, buffer.size - totalRead)
        if (read <= 0) {
          return null
        }
        totalRead += read
      }
    } ?: return null

    return buffer
  }

  private fun openInputStream(
    context: Context,
    uri: Uri,
  ): InputStream? =
    when (uri.scheme) {
      "file" -> uri.path?.let(::File)?.takeIf(File::exists)?.let(::FileInputStream)
      else -> context.contentResolver.openInputStream(uri)
    }

  private fun skipFully(
    inputStream: InputStream,
    bytesToSkip: Long,
  ): Boolean {
    var remaining = bytesToSkip
    while (remaining > 0) {
      val skipped = inputStream.skip(remaining)
      if (skipped > 0) {
        remaining -= skipped
        continue
      }

      if (inputStream.read() == -1) {
        return false
      }
      remaining--
    }

    return true
  }

  private fun computeChunkHash(buffer: ByteArray): Long {
    val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
    var hash = 0L
    while (byteBuffer.hasRemaining()) {
      hash += byteBuffer.long
    }
    return hash
  }
}
