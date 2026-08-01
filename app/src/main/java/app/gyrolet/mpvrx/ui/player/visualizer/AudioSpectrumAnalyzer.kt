/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
 */
class AudioSpectrumAnalyzer(
  val features: AudioFeatures = AudioFeatures(),
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

      if (audioSessionId > 0) {
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
          onSamplingRate = { rate -> sampleRate = rate },
        )
        visualizerManager = manager
      }
    }
  }

  /**
   * Processes live time-domain waveform byte arrays.
   * Energy is blended with FFT energy to avoid flickering from callback ordering.
   */
  fun processWaveformData(waveform: ByteArray) {
    if (waveform.isEmpty()) return
    var sumSq = 0f
    val samples = FloatArray(waveform.size.coerceAtMost(512))
    for (i in samples.indices) {
      val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
      samples[i] = sample
      sumSq += sample * sample
    }
    val rms = sqrt(sumSq / waveform.size)
    val rmsBoosted = (rms * 3.5f).coerceIn(0f, 1f)

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
   * Computes bass/mid/treble with frequency-aware band boundaries,
   * spectral centroid from magnitude-weighted frequency, and debounced beat detection.
   */
  fun processFftData(fft: ByteArray) {
    if (fft.size < 8) return
    val captureSize = fft.size
    val halfSize = captureSize / 2
    val nyquist = sampleRate / 2f
    val binHz = nyquist / halfSize

    // Frequency-aware band boundaries (Hz)
    val bassCutoffHz = 150f
    val midCutoffHz = 2000f
    val bassCutoffBin = (bassCutoffHz / binHz).toInt().coerceIn(2, halfSize)
    val midCutoffBin = (midCutoffHz / binHz).toInt().coerceIn(bassCutoffBin + 1, halfSize)

    var bassSum = 0f
    var midSum = 0f
    var trebleSum = 0f
    var bassCount = 0
    var midCount = 0
    var trebleCount = 0

    // For spectral centroid computation
    var weightedFreqSum = 0f
    var magSum = 0f

    // For spectral flux (onset detection)
    var spectralFlux = 0f

    val spectrumData = FloatArray(512)
    var k = 1
    while (k < halfSize && k < 512) {
      val realIndex = k * 2
      val imagIndex = realIndex + 1
      if (imagIndex >= captureSize) break

      val real = fft[realIndex].toInt().toFloat()
      val imaginary = fft[imagIndex].toInt().toFloat()
      val mag = (hypot(real, imaginary) / 128f).coerceIn(0f, 1f)

      spectrumData[k] = mag

      // Accumulate for spectral centroid
      val freqHz = k * binHz
      weightedFreqSum += freqHz * mag
      magSum += mag

      when {
        k < bassCutoffBin -> { bassSum += mag; bassCount++ }
        k < midCutoffBin -> { midSum += mag; midCount++ }
        else -> { trebleSum += mag; trebleCount++ }
      }
      k++
    }
    // Atomically swap the spectrum array so renderers see a consistent snapshot
    features.spectrum = spectrumData

    val bass = if (bassCount > 0) (bassSum / bassCount * 2.2f).coerceIn(0f, 1f) else 0f
    val mid = if (midCount > 0) (midSum / midCount * 2.5f).coerceIn(0f, 1f) else 0f
    val treble = if (trebleCount > 0) (trebleSum / trebleCount * 2.8f).coerceIn(0f, 1f) else 0f
    val fftEnergy = (bass * 0.5f + mid * 0.35f + treble * 0.15f).coerceIn(0f, 1f)

    // Spectral centroid: 0.0 = all low freq, 1.0 = all high freq
    val centroid = if (magSum > 0.001f) {
      (weightedFreqSum / magSum / nyquist).coerceIn(0f, 1f)
    } else {
      features.centroid
    }

    // Beat detection with debounce
    val now = System.nanoTime()
    val beatMs = (now - lastBeatNanos) / 1_000_000L
    val beatDetected = bass > 0.35f && beatMs > BEAT_DEBOUNCE_MS
    if (beatDetected) lastBeatNanos = now

    // Smooth natural audio feature response
    features.bass = features.bass * 0.3f + bass * 0.7f
    features.mid = features.mid * 0.3f + mid * 0.7f
    features.treble = features.treble * 0.3f + treble * 0.7f
    features.centroid = features.centroid * 0.6f + centroid * 0.4f

    // Blend FFT energy with existing waveform energy
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
    runCatching {
      val v = runCatching { Visualizer(0) }.getOrElse { Visualizer(sessionId) }
      visualizer = v.apply {
        captureSize = Visualizer.getCaptureSizeRange()[1]
        scalingMode = Visualizer.SCALING_MODE_NORMALIZED
        enabled = false
        setDataCaptureListener(
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
              fft?.let(onFFT)
            }
          },
          Visualizer.getMaxCaptureRate(),
          true,
          true,
        )
        enabled = true
      }
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
