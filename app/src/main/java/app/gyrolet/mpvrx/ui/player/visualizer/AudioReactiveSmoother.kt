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
  val subBass: Float,
  val bass: Float,
  val lowMid: Float,
  val mid: Float,
  val highMid: Float,
  val treble: Float,
  val centroid: Float,
  val beat: Float,
  val spectralFlux: Float,
) {
  companion object {
    val Silence = AudioFeatureFrame(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f, 0f)
  }
}

/**
 * Converts lower-rate Audio Visualizer callbacks into continuous, frame-rate-independent
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
        energy = approach(current.energy, target.energy, dt, 0.050f, 0.22f),
        subBass = approach(current.subBass, target.subBass, dt, 0.040f, 0.20f),
        bass = approach(current.bass, target.bass, dt, 0.045f, 0.20f),
        lowMid = approach(current.lowMid, target.lowMid, dt, 0.055f, 0.22f),
        mid = approach(current.mid, target.mid, dt, 0.065f, 0.24f),
        highMid = approach(current.highMid, target.highMid, dt, 0.080f, 0.26f),
        treble = approach(current.treble, target.treble, dt, 0.090f, 0.28f),
        centroid = approach(current.centroid, target.centroid, dt, 0.250f, 0.38f),
        beat = approach(current.beat, target.beat, dt, 0.020f, 0.14f),
        spectralFlux = approach(current.spectralFlux, target.spectralFlux, dt, 0.025f, 0.16f),
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
