/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.domain.thumbnail.MatroskaEmbeddedArtworkExtractor
import app.gyrolet.mpvrx.domain.thumbnail.calculateThumbnailSampleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal class MediaArtwork(
  val bytes: ByteArray,
  val mimeType: String,
  val width: Int,
  val height: Int,
) {
  fun downloadName(mediaName: String): String {
    val extension = when (mimeType) {
      "image/jpeg" -> "jpg"
      "image/png" -> "png"
      "image/webp" -> "webp"
      "image/gif" -> "gif"
      "image/bmp" -> "bmp"
      "image/heif" -> "heif"
      "image/heic" -> "heic"
      "image/avif" -> "avif"
      else -> "img"
    }
    val baseName = mediaName.substringBeforeLast('.')
      .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120).ifBlank { "artwork" }
    return "$baseName - artwork.$extension"
  }
}

internal object MediaArtworkOps {
  private const val TAG = "MediaArtworkOps"

  suspend fun readEmbeddedArtwork(context: Context, uri: Uri, displayName: String): MediaArtwork? =
    withContext(Dispatchers.IO) {
      val bytes = readRetrieverPicture(context, uri)
        ?: MatroskaEmbeddedArtworkExtractor.readArtwork(context, uri, displayName)
        ?: return@withContext null
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val mimeType = bounds.outMimeType
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mimeType == null) {
        Log.w(TAG, "The embedded image format could not be read")
        return@withContext null
      }
      MediaArtwork(bytes, mimeType, bounds.outWidth, bounds.outHeight)
    }

  fun decodePreview(artwork: MediaArtwork, maxEdge: Int): Bitmap =
    BitmapFactory.decodeByteArray(
      artwork.bytes, 0, artwork.bytes.size,
      BitmapFactory.Options().apply {
        inSampleSize = calculateThumbnailSampleSize(artwork.width, artwork.height, maxEdge)
      },
    ) ?: throw IOException("Could not decode the embedded artwork")

  /** Exports the original image, never rewriting or recompressing the media file. */
  suspend fun save(context: Context, target: Uri, artwork: MediaArtwork) =
    withContext(Dispatchers.IO) {
      context.contentResolver.openOutputStream(target, "wt")?.use { it.write(artwork.bytes) }
        ?: throw IOException("Could not open the artwork destination")
    }

  private fun readRetrieverPicture(context: Context, uri: Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
      context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        retriever.setDataSource(descriptor.fileDescriptor)
        retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
      }
    } catch (error: IOException) {
      Log.w(TAG, "Could not open media for artwork", error)
      null
    } catch (error: RuntimeException) {
      Log.w(TAG, "Android could not read embedded artwork", error)
      null
    } finally {
      try {
        retriever.release()
      } catch (error: RuntimeException) {
        Log.w(TAG, "Could not release the artwork retriever", error)
      }
    }
  }
}
