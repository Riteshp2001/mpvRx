/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.framecapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image's own pixel size, as read from its file header. */
data class ImageDimensions(
  val width: Int,
  val height: Int,
) {
  companion object {
    /**
     * What a snapshot is stamped with when its image cannot be read at all — normally because the
     * gallery file was deleted.
     *
     * Writing this rather than leaving the row blank is what stops the mosaic's backfill from probing
     * the dead file again on the next visit: the row now reads as settled. A non-positive size
     * already means "shape unknown" to every reader, so nothing else has to know.
     */
    val UNREADABLE = ImageDimensions(0, 0)
  }
}

/**
 * Decodes snapshot images from either a MediaStore URI or a plain file path.
 *
 * Every read is two-pass: bounds first, then a sampled decode. A frame is a full-resolution video
 * still — five to ten megapixels is routine — so decoding one at native size into a grid cell would
 * blow the heap within a screenful.
 */
object SnapshotImageLoader {

  suspend fun load(
    context: Context,
    imageUri: String?,
    imagePath: String?,
    targetMaxPx: Int,
    force: Boolean = false,
  ): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
      val bounds = readBounds(context, imageUri, imagePath, force) ?: return@runCatching null

      val options =
        BitmapFactory.Options().apply {
          inSampleSize = sampleSizeFor(bounds.first, bounds.second, targetMaxPx)
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      decode(context, imageUri, imagePath, options, force)
    }.getOrNull()
  }

  /**
   * The image's own pixel size, read from the file header without decoding it.
   *
   * This is what the mosaic tiles by, so it has to be the true size rather than the size of a
   * downsampled decode — a sampled bitmap's edges round to whole pixels, which is enough to move a
   * frame across the wide/tall line.
   *
   * Never forced: a header-only pass always reports a null bitmap, so `decode` cannot tell a read
   * that worked from one that failed, and a forced pass would fall through to the file even when the
   * URI read fine — letting a corrupt file at the same path overwrite a good answer.
   */
  suspend fun readDimensions(
    context: Context,
    imageUri: String?,
    imagePath: String?,
  ): ImageDimensions? = withContext(Dispatchers.IO) {
    runCatching { readBounds(context, imageUri, imagePath, force = false) }.getOrNull()?.let { (width, height) ->
      ImageDimensions(width, height)
    }
  }

  /**
   * Width and height from a header-only pass, or null when the image could not be read.
   *
   * A bounds-only decode always yields a null bitmap and reports the size through the options object
   * instead, so its return value says nothing about whether the read worked — the dimensions do.
   */
  private fun readBounds(
    context: Context,
    imageUri: String?,
    imagePath: String?,
    force: Boolean,
  ): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(context, imageUri, imagePath, bounds, force)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return bounds.outWidth to bounds.outHeight
  }

  private fun decode(
    context: Context,
    imageUri: String?,
    imagePath: String?,
    options: BitmapFactory.Options,
    force: Boolean,
  ): Bitmap? {
    if (imageUri != null) {
      val uri = Uri.parse(imageUri)
      val bitmap =
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
          .getOrNull()
      if (bitmap != null || !force) return bitmap
    }
    val path = imagePath ?: return null
    if (!File(path).exists()) return null
    return BitmapFactory.decodeFile(path, options)
  }

  /**
   * Largest power-of-two divisor that keeps the longer edge at or above [targetMaxPx]. Matches the
   * rounding used by `calculateThumbnailSampleSize` in the thumbnail package.
   */
  internal fun sampleSizeFor(width: Int, height: Int, targetMaxPx: Int): Int {
    if (width <= targetMaxPx && height <= targetMaxPx) return 1
    var sample = 1
    val maxDimension = maxOf(width, height)
    while (maxDimension / (sample * 2) >= targetMaxPx) sample *= 2
    return sample
  }

  /** Grid thumbnails stay small; §4.3 caps them at 256 px. */
  const val THUMBNAIL_MAX_PX = 256

  /**
   * A mosaic cell is wider than a grid cell — a justified line can hand one frame half the screen —
   * so a 256 px thumbnail stretched over it reads soft. This is the same pixels-per-dp the square
   * grid gets, at the wider span.
   *
   * Deliberately no higher. Sampling rounds the longer edge up to the next power of two, so this
   * yields a 384–768 px bitmap; stepping up to 512 lands a 4K frame on 960 px, which is four times
   * the pixels and four times the heap held per cached tile, for a cell that is 400–600 px wide.
   */
  const val MOSAIC_THUMBNAIL_MAX_PX = 384

  /** The detail viewer wants detail but not the native 4K frame. */
  const val VIEWER_MAX_PX = 2048
}
