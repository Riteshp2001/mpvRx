/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import kotlin.math.abs

internal const val MAX_THUMBNAIL_SIZE = 512
internal const val THUMBNAIL_JPEG_QUALITY = 90

/**
 * Cache-key name of thumbnail quality. Thumbnails have no size limit: each is decoded at the size
 * its card shows. A new name keeps them apart from thumbnails capped by the old quality setting.
 */
internal const val THUMBNAIL_QUALITY_KEY = "Full"

internal fun calculateThumbnailSampleSize(
  width: Int,
  height: Int,
  maxSize: Int = MAX_THUMBNAIL_SIZE,
  targetWidth: Int = 0,
  targetHeight: Int = 0,
): Int {
  var inSampleSize = 1
  val maxDimension = maxOf(width, height)
  while (maxDimension / (inSampleSize * 2) >= maxSize) {
    inSampleSize *= 2
  }
  // A disk thumbnail may have been created for a much larger grid cell. Decode only enough
  // pixels to cover this request, preserving both axes so portrait/square crops stay sharp.
  if (targetWidth > 0 && targetHeight > 0) {
    while (width / (inSampleSize * 2) >= targetWidth &&
      height / (inSampleSize * 2) >= targetHeight
    ) {
      inSampleSize *= 2
    }
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
