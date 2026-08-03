/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

/**
 * Shared audio features extracted from the live spectrum.
 *
 * Written by the analyzer thread, read by renderers on the GL thread.
 * [FloatArray] fields are snapshot-copied to avoid torn reads.
 */
class AudioFeatures {
  @Volatile var energy: Float = 0f

  @Volatile var bass: Float = 0f

  @Volatile var mid: Float = 0f

  @Volatile var treble: Float = 0f

  @Volatile var beat: Float = 0f

  @Volatile var centroid: Float = 0.35f

  @Volatile var active: Boolean = false

  @Volatile var lastCaptureNanos: Long = 0L

  /**
   * Raw magnitude spectrum (512 bins, normalized 0..1).
   * Updated atomically by swapping the array reference.
   * Renderers should snapshot this array before using it.
   */
  @Volatile var spectrum: FloatArray = FloatArray(512)

  /**
   * Raw waveform samples (normalized -1..1).
   * Updated atomically by swapping the array reference.
   */
  @Volatile var waveform: FloatArray = FloatArray(512)

  fun markCaptureReceived() {
    lastCaptureNanos = System.nanoTime()
    active = true
  }

  fun markCaptureStarted() {
    lastCaptureNanos = System.nanoTime()
    active = false
  }

  fun hasRecentCapture(maxAgeNanos: Long): Boolean {
    val capturedAt = lastCaptureNanos
    return capturedAt != 0L && System.nanoTime() - capturedAt <= maxAgeNanos
  }

  fun reset() {
    energy = 0f
    bass = 0f
    mid = 0f
    treble = 0f
    beat = 0f
    centroid = 0.35f
    active = false
    lastCaptureNanos = 0L
    spectrum = FloatArray(512)
    waveform = FloatArray(512)
  }

  fun decay(
    factor: Float,
    beatFactor: Float = factor,
  ) {
    bass *= factor
    mid *= factor
    treble *= factor
    energy *= factor
    beat *= beatFactor
    active = false
  }
}
