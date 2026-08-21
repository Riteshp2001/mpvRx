/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Audio spectrum analyzer for mpvRx using [android.media.audiofx.Visualizer].
 *
 * Captures live time-domain PCM waveforms and frequency-domain FFT bytes from the output mix / session ID
 * to drive [AudioFeatures] for 60 FPS visualizer rendering.
 *
 * Callers must supply the shared [AudioFeatures] instance; the analyzer never owns a private one so
 * every renderer, seekbar and capture effect observes the same feature state.
 */
class AudioSpectrumAnalyzer(
  val features: AudioFeatures,
) {
  private var visualizerManager: VisualizerManager? = null
  private var lastBeatNanos = 0L
  private var sampleRate = 44100

  private companion object {
    const val BEAT_DEBOUNCE_MS = 120L
    const val ENERGY_WAVEFORM_WEIGHT = 0.35f
    const val ENERGY_FFT_WEIGHT = 0.65f
  }

  @Synchronized
  fun start(audioSessionId: Int): Result<Unit> {
    stop(resetFeatures = false)
    return runCatching {
      features.markCaptureStarted()

      val manager = VisualizerManager(audioSessionId)
      manager.start(
        onWaveform = { waveBytes ->
          if (waveBytes.isNotEmpty()) {
            processWaveformData(waveBytes)
          }
        },
        onFFT = { fftBytes ->
          if (fftBytes.isNotEmpty()) {
            processFftData(fftBytes)
          }
        },
        // Android reports this callback value in milliHertz, while band cutoffs use Hz.
        onSamplingRate = { rate -> sampleRate = (rate / 1000).coerceAtLeast(8_000) },
      )
      visualizerManager = manager
    }
  }

  private val prevSpectrum = FloatArray(512)
  private var peakEnergyTracker = 0.25f
  private var avgFluxTracker = 0.05f

  /**
   * Processes live time-domain waveform byte arrays.
   * Applies Hann windowing to RMS energy computation.
   * Energy is blended with FFT energy to avoid flickering from callback ordering.
   */
  fun processWaveformData(waveform: ByteArray) {
    if (waveform.isEmpty()) return
    val count = waveform.size.coerceAtMost(512)
    var sumSq = 0f
    val samples = FloatArray(count)
    val tau = (2.0 * Math.PI).toFloat()

    for (i in 0 until count) {
      val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
      // Hann window to smooth boundary discontinuities
      val window = 0.5f * (1f - kotlin.math.cos(tau * i / (count - 1)))
      val windowedSample = sample * window
      samples[i] = sample
      sumSq += windowedSample * windowedSample
    }
    val rms = sqrt(sumSq / count)
    // Scale RMS with AGC tracker
    val rmsBoosted = (rms / (peakEnergyTracker * 0.5f + 0.05f)).coerceIn(0f, 1f)

    // Atomically swap the waveform array so renderers see a consistent snapshot
    features.waveform = samples

    // Blend waveform energy with FFT energy so they don't fight each other
    val currentFftEnergy = features.energy
    features.energy = if (currentFftEnergy > 0.01f) {
      (currentFftEnergy * ENERGY_FFT_WEIGHT + rmsBoosted * ENERGY_WAVEFORM_WEIGHT).coerceIn(0f, 1f)
    } else {
      rmsBoosted
    }
    features.active = true
    features.markCaptureReceived()
  }

  /**
   * Processes live FFT byte arrays.
   * Computes sub-bass, bass, low-mid, mid, high-mid, and treble bands with frequency-aware cutoffs,
   * 64 logarithmic spectrum bands, spectral flux for transient beat detection, and AGC.
   */
  fun processFftData(fft: ByteArray) {
    if (fft.size < 8) return
    val captureSize = fft.size
    val halfSize = captureSize / 2
    val nyquist = sampleRate / 2f
    val binHz = nyquist / halfSize

    // Frequency-aware band cutoffs (Hz)
    val subBassCutoffBin = (60f / binHz).toInt().coerceIn(1, halfSize)
    val bassCutoffBin = (250f / binHz).toInt().coerceIn(subBassCutoffBin + 1, halfSize)
    val lowMidCutoffBin = (500f / binHz).toInt().coerceIn(bassCutoffBin + 1, halfSize)
    val midCutoffBin = (2000f / binHz).toInt().coerceIn(lowMidCutoffBin + 1, halfSize)
    val highMidCutoffBin = (6000f / binHz).toInt().coerceIn(midCutoffBin + 1, halfSize)

    var subBassSum = 0f; var subBassCount = 0
    var bassSum = 0f; var bassCount = 0
    var lowMidSum = 0f; var lowMidCount = 0
    var midSum = 0f; var midCount = 0
    var highMidSum = 0f; var highMidCount = 0
    var trebleSum = 0f; var trebleCount = 0

    var weightedFreqSum = 0f
    var magSum = 0f
    var spectralFlux = 0f

    val spectrumData = FloatArray(512)
    val logSpectrumData = FloatArray(64)
    val logBinCounts = IntArray(64)

    val minLogHz = 20f
    val maxLogHz = nyquist.coerceAtMost(20000f)
    val logRatio = kotlin.math.ln(maxLogHz / minLogHz)

    var k = 1
    while (k < halfSize && k < 512) {
      val realIndex = k * 2
      val imagIndex = realIndex + 1
      if (imagIndex >= captureSize) break

      val real = fft[realIndex].toInt().toFloat()
      val imaginary = fft[imagIndex].toInt().toFloat()
      val rawMag = hypot(real, imaginary) / 128f

      spectrumData[k] = rawMag

      // Spectral flux (positive difference from previous frame)
      val diff = rawMag - prevSpectrum[k]
      if (diff > 0f) {
        spectralFlux += diff
      }
      prevSpectrum[k] = rawMag

      val freqHz = k * binHz
      weightedFreqSum += freqHz * rawMag
      magSum += rawMag

      // Frequency band accumulation
      when {
        k < subBassCutoffBin -> { subBassSum += rawMag; subBassCount++ }
        k < bassCutoffBin -> { bassSum += rawMag; bassCount++ }
        k < lowMidCutoffBin -> { lowMidSum += rawMag; lowMidCount++ }
        k < midCutoffBin -> { midSum += rawMag; midCount++ }
        k < highMidCutoffBin -> { highMidSum += rawMag; highMidCount++ }
        else -> { trebleSum += rawMag; trebleCount++ }
      }

      // Logarithmic band mapping
      if (freqHz in minLogHz..maxLogHz) {
        val logIndex = ((kotlin.math.ln(freqHz / minLogHz) / logRatio) * 63.99f).toInt().coerceIn(0, 63)
        logSpectrumData[logIndex] += rawMag
        logBinCounts[logIndex]++
      }

      k++
    }

    // Averaging and AGC normalization for 64 logarithmic spectrum bands
    var frameMaxMag = 0.01f
    for (i in 0 until 64) {
      if (logBinCounts[i] > 0) {
        logSpectrumData[i] /= logBinCounts[i]
      }
      if (logSpectrumData[i] > frameMaxMag) {
        frameMaxMag = logSpectrumData[i]
      }
    }

    // Adaptive peak energy tracking for Automatic Gain Control (AGC)
    peakEnergyTracker = if (frameMaxMag > peakEnergyTracker) {
      peakEnergyTracker * 0.7f + frameMaxMag * 0.3f
    } else {
      (peakEnergyTracker * 0.995f).coerceAtLeast(0.15f)
    }
    val agcScale = 1.0f / peakEnergyTracker

    for (i in 0 until 64) {
      logSpectrumData[i] = (logSpectrumData[i] * agcScale).coerceIn(0f, 1f)
    }
    for (i in spectrumData.indices) {
      spectrumData[i] = (spectrumData[i] * agcScale).coerceIn(0f, 1f)
    }

    features.spectrum = spectrumData
    features.logSpectrum = logSpectrumData

    val subBass = if (subBassCount > 0) (subBassSum / subBassCount * agcScale * 1.5f).coerceIn(0f, 1f) else 0f
    val bass = if (bassCount > 0) (bassSum / bassCount * agcScale * 1.4f).coerceIn(0f, 1f) else 0f
    val lowMid = if (lowMidCount > 0) (lowMidSum / lowMidCount * agcScale * 1.3f).coerceIn(0f, 1f) else 0f
    val mid = if (midCount > 0) (midSum / midCount * agcScale * 1.2f).coerceIn(0f, 1f) else 0f
    val highMid = if (highMidCount > 0) (highMidSum / highMidCount * agcScale * 1.1f).coerceIn(0f, 1f) else 0f
    val treble = if (trebleCount > 0) (trebleSum / trebleCount * agcScale * 1.0f).coerceIn(0f, 1f) else 0f

    val fftEnergy = (subBass * 0.25f + bass * 0.35f + lowMid * 0.15f + mid * 0.15f + treble * 0.10f).coerceIn(0f, 1f)

    val centroid = if (magSum > 0.001f) {
      (weightedFreqSum / magSum / nyquist).coerceIn(0f, 1f)
    } else {
      features.centroid
    }

    // Adaptive Spectral Flux beat & onset detection
    avgFluxTracker = avgFluxTracker * 0.92f + spectralFlux * 0.08f
    val now = System.nanoTime()
    val beatMs = (now - lastBeatNanos) / 1_000_000L
    val fluxThreshold = (avgFluxTracker * 1.35f + 0.12f).coerceAtLeast(0.18f)
    val beatDetected = (spectralFlux > fluxThreshold || subBass > 0.45f) && beatMs > BEAT_DEBOUNCE_MS
    if (beatDetected) lastBeatNanos = now

    features.subBass = features.subBass * 0.2f + subBass * 0.8f
    features.bass = features.bass * 0.2f + bass * 0.8f
    features.lowMid = features.lowMid * 0.2f + lowMid * 0.8f
    features.mid = features.mid * 0.2f + mid * 0.8f
    features.highMid = features.highMid * 0.2f + highMid * 0.8f
    features.treble = features.treble * 0.2f + treble * 0.8f
    features.spectralFlux = features.spectralFlux * 0.3f + spectralFlux * 0.7f
    features.centroid = features.centroid * 0.6f + centroid * 0.4f

    val currentWaveformEnergy = features.energy
    features.energy = if (currentWaveformEnergy > 0.01f) {
      (currentWaveformEnergy * ENERGY_WAVEFORM_WEIGHT + fftEnergy * ENERGY_FFT_WEIGHT).coerceIn(0f, 1f)
    } else {
      fftEnergy
    }

    features.beat = if (beatDetected) 1f else 0f
    features.active = true
    features.markCaptureReceived()
  }

  @Synchronized
  fun stop(resetFeatures: Boolean = true) {
    visualizerManager?.release()
    visualizerManager = null
    if (resetFeatures) {
      features.reset()
    } else {
      features.active = false
    }
  }
}

/**
 * Lightweight manager attached to AudioTrack session IDs or global session 0.
 */
class VisualizerManager(
  private val sessionId: Int,
) {
  private var visualizer: Visualizer? = null

  fun start(
    onWaveform: (ByteArray) -> Unit,
    onFFT: (ByteArray) -> Unit,
    onSamplingRate: ((Int) -> Unit)? = null,
  ) {
    release()
    val v = Visualizer(sessionId)
    try {
      v.captureSize = Visualizer.getCaptureSizeRange()[1]
      v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
      v.enabled = false
      v.setDataCaptureListener(
        object : Visualizer.OnDataCaptureListener {
          override fun onWaveFormDataCapture(
            visualizer: Visualizer?,
            waveform: ByteArray?,
            samplingRate: Int,
          ) {
            onSamplingRate?.invoke(samplingRate)
            waveform?.let(onWaveform)
          }

          override fun onFftDataCapture(
            visualizer: Visualizer?,
            fft: ByteArray?,
            samplingRate: Int,
          ) {
            onSamplingRate?.invoke(samplingRate)
            fft?.let(onFFT)
          }
        },
        Visualizer.getMaxCaptureRate(),
        true,
        true,
      )
      v.enabled = true
      visualizer = v
    } catch (error: Throwable) {
      runCatching { v.release() }
      throw error
    }
  }

  fun release() {
    runCatching {
      visualizer?.enabled = false
      visualizer?.release()
    }
    visualizer = null
  }
}
