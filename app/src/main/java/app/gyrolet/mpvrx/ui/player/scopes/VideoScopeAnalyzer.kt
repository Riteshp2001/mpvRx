/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.scopes

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.ln
import kotlin.math.max

internal object VideoScopeAnalyzer {
  private const val OUTPUT_WIDTH = 320
  private const val OUTPUT_HEIGHT = 180
  private const val VECTOR_SIZE = 220
  private const val PARADE_GAP = 2
  private const val PARADE_CHANNEL_WIDTH = (OUTPUT_WIDTH - PARADE_GAP * 3) / 4

  private val analysisLock = Any()
  private var sourcePixels = IntArray(0)
  private val waveformBins = IntArray(OUTPUT_WIDTH * OUTPUT_HEIGHT)
  private val waveformSumRed = LongArray(waveformBins.size)
  private val waveformSumGreen = LongArray(waveformBins.size)
  private val waveformSumBlue = LongArray(waveformBins.size)
  private val waveformOutput = IntArray(waveformBins.size)
  private val paradeBins = Array(4) { IntArray(PARADE_CHANNEL_WIDTH * OUTPUT_HEIGHT) }
  private val paradeOutput = IntArray(OUTPUT_WIDTH * OUTPUT_HEIGHT)
  private val vectorBins = IntArray(VECTOR_SIZE * VECTOR_SIZE)
  private val vectorSumRed = LongArray(vectorBins.size)
  private val vectorSumGreen = LongArray(vectorBins.size)
  private val vectorSumBlue = LongArray(vectorBins.size)
  private val vectorOutput = IntArray(vectorBins.size)
  private val paradeColors =
    intArrayOf(
      Color.rgb(255, 45, 45),
      Color.rgb(45, 255, 70),
      Color.rgb(65, 95, 255),
      Color.rgb(225, 225, 225),
    )

  fun analyze(
    source: Bitmap,
    mode: VideoScopeMode,
  ): Bitmap =
    synchronized(analysisLock) {
      val sourcePixelCount = source.width * source.height
      if (sourcePixels.size < sourcePixelCount) sourcePixels = IntArray(sourcePixelCount)
      source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
      when (mode) {
        VideoScopeMode.LumaWaveform -> lumaWaveform(source.width, source.height)
        VideoScopeMode.RgbyParade -> rgbyParade(source.width, source.height)
        VideoScopeMode.Vectorscope -> vectorscope(sourcePixelCount)
      }
    }

  private fun lumaWaveform(
    sourceWidth: Int,
    sourceHeight: Int,
  ): Bitmap {
    waveformBins.fill(0)
    waveformSumRed.fill(0)
    waveformSumGreen.fill(0)
    waveformSumBlue.fill(0)
    waveformOutput.fill(0)

    for (y in 0 until sourceHeight) {
      val row = y * sourceWidth
      for (x in 0 until sourceWidth) {
        val color = sourcePixels[row + x]
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val luma = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255f
        val column = x * OUTPUT_WIDTH / sourceWidth
        val level = (luma * (OUTPUT_HEIGHT - 1)).toInt().coerceIn(0, OUTPUT_HEIGHT - 1)
        val index = column * OUTPUT_HEIGHT + level
        waveformBins[index]++
        waveformSumRed[index] += red
        waveformSumGreen[index] += green
        waveformSumBlue[index] += blue
      }
    }

    val maximum = waveformBins.maxOrNull()?.coerceAtLeast(1) ?: 1
    val logMaximum = ln(1f + maximum)
    for (column in 0 until OUTPUT_WIDTH) {
      for (level in 0 until OUTPUT_HEIGHT) {
        val sourceIndex = column * OUTPUT_HEIGHT + level
        val count = waveformBins[sourceIndex]
        if (count == 0) continue
        val intensity = (ln(1f + count) / logMaximum * 2.4f).coerceIn(0f, 1f)
        val averageRed = waveformSumRed[sourceIndex].toFloat() / count
        val averageGreen = waveformSumGreen[sourceIndex].toFloat() / count
        val averageBlue = waveformSumBlue[sourceIndex].toFloat() / count
        val colorMax = max(averageRed, max(averageGreen, averageBlue)).coerceAtLeast(1f)
        val outputIndex = (OUTPUT_HEIGHT - 1 - level) * OUTPUT_WIDTH + column
        waveformOutput[outputIndex] =
          Color.argb(
            (intensity * 255).toInt(),
            (averageRed / colorMax * 255).toInt(),
            (averageGreen / colorMax * 255).toInt(),
            (averageBlue / colorMax * 255).toInt(),
          )
      }
    }
    return waveformOutput.toBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT)
  }

  private fun rgbyParade(
    sourceWidth: Int,
    sourceHeight: Int,
  ): Bitmap {
    paradeBins.forEach { it.fill(0) }
    paradeOutput.fill(0)

    for (y in 0 until sourceHeight) {
      val row = y * sourceWidth
      for (x in 0 until sourceWidth) {
        val color = sourcePixels[row + x]
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val column = x * PARADE_CHANNEL_WIDTH / sourceWidth
        val columnOffset = column * OUTPUT_HEIGHT
        paradeBins[0][columnOffset + red * (OUTPUT_HEIGHT - 1) / 255]++
        paradeBins[1][columnOffset + green * (OUTPUT_HEIGHT - 1) / 255]++
        paradeBins[2][columnOffset + blue * (OUTPUT_HEIGHT - 1) / 255]++
        val lumaLevel =
          ((red * 0.2126f + green * 0.7152f + blue * 0.0722f) * (OUTPUT_HEIGHT - 1) / 255f)
            .toInt()
            .coerceIn(0, OUTPUT_HEIGHT - 1)
        paradeBins[3][columnOffset + lumaLevel]++
      }
    }

    val maximum = paradeBins.maxOf { it.maxOrNull() ?: 0 }.coerceAtLeast(1)
    for (channel in paradeBins.indices) {
      val xOffset = channel * (PARADE_CHANNEL_WIDTH + PARADE_GAP)
      for (column in 0 until PARADE_CHANNEL_WIDTH) {
        for (level in 0 until OUTPUT_HEIGHT) {
          val count = paradeBins[channel][column * OUTPUT_HEIGHT + level]
          if (count == 0) continue
          val intensity = (count.toFloat() / (maximum * 0.22f)).coerceIn(0f, 1f)
          val tint = paradeColors[channel]
          paradeOutput[(OUTPUT_HEIGHT - 1 - level) * OUTPUT_WIDTH + xOffset + column] =
            Color.argb(
              255,
              (Color.red(tint) * intensity).toInt(),
              (Color.green(tint) * intensity).toInt(),
              (Color.blue(tint) * intensity).toInt(),
            )
        }
      }
    }
    return paradeOutput.toBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT)
  }

  private fun vectorscope(sourcePixelCount: Int): Bitmap {
    vectorBins.fill(0)
    vectorSumRed.fill(0)
    vectorSumGreen.fill(0)
    vectorSumBlue.fill(0)
    vectorOutput.fill(0)
    val half = VECTOR_SIZE / 2f
    val scale = half * 0.9f

    for (sourceIndex in 0 until sourcePixelCount) {
      val color = sourcePixels[sourceIndex]
      val red = Color.red(color) / 255f
      val green = Color.green(color) / 255f
      val blue = Color.blue(color) / 255f
      val cb = -0.1146f * red - 0.3854f * green + 0.5f * blue
      val cr = 0.5f * red - 0.4542f * green - 0.0458f * blue
      val x = (half + cb * scale * 2f).toInt()
      val y = (half - cr * scale * 2f).toInt()
      if (x !in 0 until VECTOR_SIZE || y !in 0 until VECTOR_SIZE) continue
      val index = y * VECTOR_SIZE + x
      vectorBins[index]++
      vectorSumRed[index] += Color.red(color)
      vectorSumGreen[index] += Color.green(color)
      vectorSumBlue[index] += Color.blue(color)
    }

    val maximum = vectorBins.maxOrNull()?.coerceAtLeast(1) ?: 1
    val logMaximum = ln(1f + maximum)
    for (index in vectorBins.indices) {
      val count = vectorBins[index]
      if (count == 0) continue
      val intensity = (ln(1f + count) / logMaximum * 2.8f).coerceIn(0f, 1f)
      val averageRed = vectorSumRed[index].toFloat() / count
      val averageGreen = vectorSumGreen[index].toFloat() / count
      val averageBlue = vectorSumBlue[index].toFloat() / count
      val colorMax = max(averageRed, max(averageGreen, averageBlue)).coerceAtLeast(1f)
      vectorOutput[index] =
        Color.argb(
          (intensity * 255).toInt(),
          (averageRed / colorMax * 255).toInt(),
          (averageGreen / colorMax * 255).toInt(),
          (averageBlue / colorMax * 255).toInt(),
        )
    }
    return vectorOutput.toBitmap(VECTOR_SIZE, VECTOR_SIZE)
  }

  private fun IntArray.toBitmap(
    width: Int,
    height: Int,
  ): Bitmap = Bitmap.createBitmap(this, width, height, Bitmap.Config.ARGB_8888)
}
