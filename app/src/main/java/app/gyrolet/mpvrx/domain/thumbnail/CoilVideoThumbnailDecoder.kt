package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.ContentMetadata
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import okio.FileSystem
import kotlin.math.abs

class CoilVideoThumbnailDecoder(
  private val source: ImageSource,
  private val options: Options,
  private val strategy: ThumbnailStrategy,
  private val diskCache: Lazy<DiskCache?>,
) : Decoder {
  private val diskCacheKey: String
    get() =
      options.diskCacheKey ?: run {
        val metadata = source.metadata
        when {
          metadata is ContentMetadata -> metadata.uri.toAndroidUri().toString()
          source.fileSystem === FileSystem.SYSTEM -> source.file().toFile().path
          else -> error("Unsupported thumbnail source")
        }
      }

  @OptIn(ExperimentalCoilApi::class)
  override suspend fun decode(): DecodeResult {
    readFromDiskCache()?.use { snapshot ->
      val cachedBitmap =
        snapshot.data
          .toFile()
          .inputStream()
          .use(BitmapFactory::decodeStream)

      if (cachedBitmap != null) {
        return DecodeResult(
          image = cachedBitmap.toDrawable(options.context.resources).asImage(),
          isSampled = false,
        )
      }
    }

    return MediaMetadataRetriever().use { retriever ->
      retriever.setDataSource(source)

      val embeddedPicture =
        if (strategy.prefersEmbeddedPicture()) {
          retriever.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          }
        } else {
          null
        }

      val rawBitmap =
        when (strategy) {
          ThumbnailStrategy.FirstFrame -> retriever.getFrameAtTime(0)
          is ThumbnailStrategy.FrameAtPercentage -> {
            retriever.getFrameAtTime(frameTimeMicros(retriever, strategy.percentage))
          }

          is ThumbnailStrategy.Hybrid -> {
            val firstFrame = retriever.getFrameAtTime(0)
            if (firstFrame != null && isMostlySolid(firstFrame)) {
              firstFrame.recycle()
              retriever.getFrameAtTime(frameTimeMicros(retriever, strategy.percentage))
            } else {
              firstFrame
            }
          }

          ThumbnailStrategy.EmbeddedOrFirstFrame -> embeddedPicture ?: retriever.getFrameAtTime(0)
        } ?: throw IllegalStateException("Failed to decode video thumbnail")

      val rotatedBitmap = rotateBitmapIfNeeded(retriever, rawBitmap)
      val cachedBitmap = writeToDiskCache(rotatedBitmap)

      DecodeResult(
        image = cachedBitmap.toDrawable(options.context.resources).asImage(),
        isSampled = false,
      )
    }
  }

  private fun frameTimeMicros(
    retriever: MediaMetadataRetriever,
    percentage: Float,
  ): Long {
    val durationMs =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    return (durationMs * percentage.coerceIn(0f, 1f) * 1000).toLong()
  }

  private fun MediaMetadataRetriever.setDataSource(source: ImageSource) {
    val metadata = source.metadata
    when {
      metadata is ContentMetadata -> setDataSource(options.context, metadata.uri.toAndroidUri())
      source.fileSystem === FileSystem.SYSTEM -> setDataSource(source.file().toFile().path)
      else -> error("Unsupported thumbnail source")
    }
  }

  private fun rotateBitmapIfNeeded(
    retriever: MediaMetadataRetriever,
    bitmap: Bitmap,
  ): Bitmap {
    val rotation =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        ?: return bitmap
    if (rotation == 0) {
      return bitmap
    }

    val matrix =
      Matrix().apply {
        postRotate(rotation.toFloat())
      }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) {
      bitmap.recycle()
    }
    return rotated
  }

  private fun readFromDiskCache(): DiskCache.Snapshot? =
    if (options.diskCachePolicy.readEnabled) {
      diskCache.value?.openSnapshot(diskCacheKey)
    } else {
      null
    }

  private fun writeToDiskCache(inBitmap: Bitmap): Bitmap {
    if (!options.diskCachePolicy.writeEnabled) {
      return inBitmap
    }

    val editor = diskCache.value?.openEditor(diskCacheKey) ?: return inBitmap
    try {
      editor.data.toFile().outputStream().use { output ->
        inBitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
      }
      editor.commitAndOpenSnapshot()?.use { snapshot ->
        val outBitmap =
          snapshot.data
            .toFile()
            .inputStream()
            .use(BitmapFactory::decodeStream)
        if (outBitmap != null) {
          if (outBitmap != inBitmap) {
            inBitmap.recycle()
          }
          return outBitmap
        }
      }
    } catch (_: Exception) {
      runCatching { editor.abort() }
    }

    return inBitmap
  }

  class Factory(
    private val thumbnailStrategy: () -> ThumbnailStrategy,
  ) : Decoder.Factory {
    override fun create(
      result: SourceFetchResult,
      options: Options,
      imageLoader: ImageLoader,
    ): Decoder? {
      if (!isApplicable(result)) {
        return null
      }

      return CoilVideoThumbnailDecoder(
        source = result.source,
        options = options,
        strategy = thumbnailStrategy(),
        diskCache = lazy { imageLoader.diskCache },
      )
    }

    private fun isApplicable(result: SourceFetchResult): Boolean {
      val mimeType = result.mimeType
      if (mimeType != null && mimeType.startsWith("video/")) {
        return true
      }

      val metadata = result.source.metadata
      val sourcePath =
        when {
          metadata is ContentMetadata -> metadata.uri.toString()
          result.source.fileSystem === FileSystem.SYSTEM -> result.source.file().toFile().path
          else -> null
        } ?: return false

      val extension = sourcePath.substringAfterLast('.', "").lowercase()
      return FileTypeUtils.VIDEO_EXTENSIONS.contains(extension)
    }
  }
}

sealed class ThumbnailStrategy {
  abstract val cacheKey: String

  data object FirstFrame : ThumbnailStrategy() {
    override val cacheKey: String = "first_frame"
  }

  data class FrameAtPercentage(
    val percentage: Float = 0.33f,
  ) : ThumbnailStrategy() {
    override val cacheKey: String = "frame_${percentage}"
  }

  data class Hybrid(
    val percentage: Float = 0.33f,
  ) : ThumbnailStrategy() {
    override val cacheKey: String = "hybrid_${percentage}"
  }

  data object EmbeddedOrFirstFrame : ThumbnailStrategy() {
    override val cacheKey: String = "embedded_or_first_frame"
  }
}

private fun isMostlySolid(
  bitmap: Bitmap,
  threshold: Float = 0.7f,
): Boolean {
  val width = bitmap.width
  val height = bitmap.height
  if (width <= 0 || height <= 0) {
    return false
  }

  val marginX = width / 10
  val marginY = height / 10
  val sampleAreaRight = width - marginX
  val sampleAreaBottom = height - marginY
  val gridSize = 10
  val stepX = (sampleAreaRight - marginX) / gridSize
  val stepY = (sampleAreaBottom - marginY) / gridSize

  if (stepX <= 0 || stepY <= 0) {
    return false
  }

  val sampledColors = mutableListOf<Int>()
  for (x in 0 until gridSize) {
    for (y in 0 until gridSize) {
      val pixelX = marginX + x * stepX
      val pixelY = marginY + y * stepY
      if (pixelX in 0 until width && pixelY in 0 until height) {
        sampledColors += bitmap.getPixel(pixelX, pixelY)
      }
    }
  }

  if (sampledColors.isEmpty()) {
    return false
  }

  val referenceColor = sampledColors.first()
  val referenceR = (referenceColor shr 16) and 0xFF
  val referenceG = (referenceColor shr 8) and 0xFF
  val referenceB = referenceColor and 0xFF
  val tolerance = 30

  val similarCount =
    sampledColors.count { color ->
      val r = (color shr 16) and 0xFF
      val g = (color shr 8) and 0xFF
      val b = color and 0xFF

      abs(r - referenceR) <= tolerance &&
        abs(g - referenceG) <= tolerance &&
        abs(b - referenceB) <= tolerance
    }

  return similarCount.toFloat() / sampledColors.size >= threshold
}

private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
  try {
    block(this)
  } finally {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      close()
    } else {
      release()
    }
  }

