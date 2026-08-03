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

  @Volatile var subBass: Float = 0f

  @Volatile var bass: Float = 0f

  @Volatile var lowMid: Float = 0f

  @Volatile var mid: Float = 0f

  @Volatile var highMid: Float = 0f

  @Volatile var treble: Float = 0f

  @Volatile var beat: Float = 0f

  @Volatile var spectralFlux: Float = 0f

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
   * Logarithmically grouped spectrum bands (64 bands, normalized 0..1).
   * Maps 20Hz-20kHz according to human auditory perception.
   */
  @Volatile var logSpectrum: FloatArray = FloatArray(64)

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
    subBass = 0f
    bass = 0f
    lowMid = 0f
    mid = 0f
    highMid = 0f
    treble = 0f
    beat = 0f
    spectralFlux = 0f
    centroid = 0.35f
    active = false
    lastCaptureNanos = 0L
    spectrum = FloatArray(512)
    logSpectrum = FloatArray(64)
    waveform = FloatArray(512)
  }

  fun decay(
    factor: Float,
    beatFactor: Float = factor,
  ) {
    subBass *= factor
    bass *= factor
    lowMid *= factor
    mid *= factor
    highMid *= factor
    treble *= factor
    energy *= factor
    spectralFlux *= factor
    beat *= beatFactor
    active = false
  }
}
