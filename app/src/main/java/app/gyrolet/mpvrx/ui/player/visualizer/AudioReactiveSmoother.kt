/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import kotlin.math.exp

internal data class AudioFeatureFrame(
  val energy: Float,
  val bass: Float,
  val mid: Float,
  val treble: Float,
  val centroid: Float,
  val beat: Float,
) {
  companion object {
    val Silence = AudioFeatureFrame(0f, 0f, 0f, 0f, 0.35f, 0f)
  }
}

/**
 * Converts the lower-rate Android Visualizer callbacks into continuous, frame-rate-independent
 * motion. Fast attacks retain musical transients while slower releases avoid twitchy geometry.
 */
internal class AudioReactiveSmoother {
  private var current = AudioFeatureFrame.Silence

  fun update(target: AudioFeatureFrame, deltaSeconds: Float): AudioFeatureFrame {
    val dt = deltaSeconds.coerceIn(1f / 240f, 1f / 20f)
    current = AudioFeatureFrame(
      energy = approach(current.energy, target.energy, dt, 0.075f, 0.28f),
      bass = approach(current.bass, target.bass, dt, 0.060f, 0.24f),
      mid = approach(current.mid, target.mid, dt, 0.090f, 0.30f),
      treble = approach(current.treble, target.treble, dt, 0.120f, 0.34f),
      centroid = approach(current.centroid, target.centroid, dt, 0.32f, 0.42f),
      beat = approach(current.beat, target.beat, dt, 0.035f, 0.18f),
    )
    return current
  }

  private fun approach(
    current: Float,
    target: Float,
    dt: Float,
    attackSeconds: Float,
    releaseSeconds: Float,
  ): Float {
    val timeConstant = if (target > current) attackSeconds else releaseSeconds
    val amount = 1f - exp(-dt / timeConstant)
    return (current + (target - current) * amount).coerceIn(0f, 1f)
  }
}
