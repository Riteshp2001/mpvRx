/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

private const val SAMPLE_GAP_MS = 650L
private const val SAMPLE_COUNT = 3
private const val MAX_ATTEMPTS = 16
private const val MAX_CAPTURES = 6

internal suspend fun detectBlackBars(source: BlackBarSource, cacheDir: File): VideoBlackBars? {
  var commonBars: VideoBlackBars? = null
  var previousPosition: Double? = null
  var votes = 0
  var captures = 0

  repeat(MAX_ATTEMPTS) { attempt ->
    if (attempt > 0) delay(SAMPLE_GAP_MS)
    val sample = withContext(Dispatchers.IO) { measureFrame(source, cacheDir, previousPosition) }
      ?: return@repeat
    captures++
    val (position, bars) = sample
    // Don't combine frames from opposite sides of a seek.
    if (previousPosition?.let { position < it || abs(position - it) > 4 } == true) {
      commonBars = null
      votes = 0
    }
    previousPosition = position
    if (bars != null) {
      votes++
      commonBars = commonBars?.commonWith(bars) ?: bars
    }
    // An edge with visible picture in any sample must stay; an empty intersection can't regrow.
    if ((votes >= 2 && commonBars?.isEmpty == true) || votes >= SAMPLE_COUNT) return commonBars
    if (captures >= MAX_CAPTURES) return commonBars.takeIf { votes >= 2 }
  }
  if (captures == 0) Log.w("BlackBarCrop", "No readable video frames for black bar detection")
  return commonBars.takeIf { votes >= 2 }
}

private fun measureFrame(
  source: BlackBarSource,
  cacheDir: File,
  previousPosition: Double?,
): Pair<Double, VideoBlackBars?>? {
  val file = try {
    File.createTempFile("mpvrx_black_bars_", ".png", cacheDir)
  } catch (error: IOException) {
    Log.w("BlackBarCrop", "Could not open temporary frame file", error)
    return null
  }
  try {
    val position = PlaybackSession.captureBlackBarFrame(source, file, previousPosition) ?: return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 640) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
    if (bitmap == null) {
      Log.w("BlackBarCrop", "Could not decode video frame")
      return position to null
    }
    try {
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      return position to measureBlackBars(pixels, bitmap.width, bitmap.height)
    } finally {
      bitmap.recycle()
    }
  } finally {
    file.delete()
  }
}
