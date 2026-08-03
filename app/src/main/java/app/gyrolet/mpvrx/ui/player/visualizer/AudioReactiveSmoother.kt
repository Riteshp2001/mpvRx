/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

  fun update(
    target: AudioFeatureFrame,
    deltaSeconds: Float,
  ): AudioFeatureFrame {
    val dt = deltaSeconds.coerceIn(1f / 240f, 1f / 20f)
    current =
      AudioFeatureFrame(
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
