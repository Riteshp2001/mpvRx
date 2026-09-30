/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

enum class EqualizerFilterKind {
  BELL,
  LOW_SHELF,
  HIGH_SHELF,
}

data class EqualizerFilter(
  val kind: EqualizerFilterKind,
  val frequencyHz: Int,
  val gainDb: Float,
  val q: Float,
)

fun EqualizerState.activeFilters(): List<EqualizerFilter> =
  when (mode) {
    EqualizerMode.MANUAL ->
      EQ_BAND_FREQUENCIES.mapIndexed { index, frequency ->
        EqualizerFilter(
          kind =
            when (index) {
              0 -> EqualizerFilterKind.LOW_SHELF
              EQ_BAND_FREQUENCIES.lastIndex -> EqualizerFilterKind.HIGH_SHELF
              else -> EqualizerFilterKind.BELL
            },
          frequencyHz = frequency,
          gainDb = bandGains.getOrElse(index) { 0f }.coerceIn(EQ_MIN_DB, EQ_MAX_DB),
          q = 1f,
        )
      }
    EqualizerMode.DYNAMIC -> {
      val tilt = toneX.coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS) * EQ_TONE_DB_PER_STEP
      val contour = toneY.coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS) * EQ_TONE_DB_PER_STEP
      val shelfQ = if (toneFocused) 0.9f else 0.5f
      val bellQ = if (toneFocused) 2.2f else 0.7f
      listOf(
        EqualizerFilter(EqualizerFilterKind.LOW_SHELF, 250, -tilt, shelfQ),
        EqualizerFilter(EqualizerFilterKind.BELL, 1_000, contour, bellQ),
        EqualizerFilter(EqualizerFilterKind.HIGH_SHELF, 4_000, tilt, shelfQ),
      )
    }
  }.filter { filter -> abs(filter.gainDb) >= 0.01f }

fun EqualizerState.recommendedPreampDb(): Float {
  val filters = activeFilters()
  if (filters.isEmpty()) return 0f

  var peakDb = 0f
  repeat(96) { point ->
    val fraction = point.toDouble() / 95.0
    val frequency = 20.0 * 1_000.0.pow(fraction)
    val response = filters.sumOf { filter -> filter.responseDbAt(frequency) }
    if (response > peakDb) peakDb = response.toFloat()
  }
  return -peakDb
}

private fun EqualizerFilter.responseDbAt(frequencyHz: Double): Double {
  val amplitude = 10.0.pow(gainDb / 40.0)
  val amplitudeSquared = amplitude * amplitude
  val ratio = frequencyHz / this.frequencyHz
  val ratioSquared = ratio * ratio
  val qSquared = (q * q).toDouble()
  val magnitude =
    when (kind) {
      EqualizerFilterKind.BELL -> {
        val flat = (1.0 - ratioSquared).pow(2)
        sqrt(
          (flat + ratioSquared * amplitudeSquared / qSquared) /
            (flat + ratioSquared / (amplitudeSquared * qSquared)),
        )
      }
      EqualizerFilterKind.LOW_SHELF -> {
        val common = ratioSquared * amplitude / qSquared
        amplitude *
          sqrt(
            ((amplitude - ratioSquared).pow(2) + common) /
              ((1.0 - amplitude * ratioSquared).pow(2) + common),
          )
      }
      EqualizerFilterKind.HIGH_SHELF -> {
        val common = ratioSquared * amplitude / qSquared
        amplitude *
          sqrt(
            ((1.0 - amplitude * ratioSquared).pow(2) + common) /
              ((amplitude - ratioSquared).pow(2) + common),
          )
      }
    }
  return 20.0 * log10(magnitude)
}