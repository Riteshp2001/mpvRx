package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import kotlin.math.abs

internal const val MAX_THUMBNAIL_SIZE = 512
internal const val THUMBNAIL_JPEG_QUALITY = 100

internal fun calculateThumbnailSampleSize(
  width: Int,
  height: Int,
  maxSize: Int = MAX_THUMBNAIL_SIZE,
): Int {
  if (width <= maxSize && height <= maxSize) return 1
  var inSampleSize = 1
  val maxDimension = maxOf(width, height)
  while (maxDimension / (inSampleSize * 2) >= maxSize) {
    inSampleSize *= 2
  }
  return inSampleSize
}

internal fun Bitmap.scaleToThumbnailMax(maxSize: Int = MAX_THUMBNAIL_SIZE): Bitmap {
  if (width <= maxSize && height <= maxSize) return this
  val scale = maxSize.toFloat() / maxOf(width, height)
  val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
  val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
  val scaled = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
  if (scaled !== this && !isRecycled) recycle()
  return scaled
}

internal fun isMostlySolidThumbnail(
  bitmap: Bitmap,
  threshold: Float = 0.7f,
): Boolean {
  val width = bitmap.width
  val height = bitmap.height
  if (width <= 0 || height <= 0) return false

  val marginX = width / 10
  val marginY = height / 10
  val sampleAreaRight = width - marginX
  val sampleAreaBottom = height - marginY
  val gridSize = 10
  val stepX = (sampleAreaRight - marginX) / gridSize
  val stepY = (sampleAreaBottom - marginY) / gridSize

  if (stepX <= 0 || stepY <= 0) return false

  val sampledColors = ArrayList<Int>(gridSize * gridSize)
  for (x in 0 until gridSize) {
    for (y in 0 until gridSize) {
      val pixelX = marginX + x * stepX
      val pixelY = marginY + y * stepY
      if (pixelX in 0 until width && pixelY in 0 until height) {
        sampledColors += bitmap.getPixel(pixelX, pixelY)
      }
    }
  }

  if (sampledColors.isEmpty()) return false

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
