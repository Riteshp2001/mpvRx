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
        )
        visualizerManager = manager
      }
    }
  }

  /**
   * Processes live time-domain waveform byte arrays.
   */
  fun processWaveformData(waveform: ByteArray) {
    if (waveform.isEmpty()) return
    var sumSq = 0f
    for (i in waveform.indices) {
      val sample = ((waveform[i].toInt() and 0xFF) - 128) / 128f
      sumSq += sample * sample
    }
    val rms = sqrt(sumSq / waveform.size)
    val rmsBoosted = (rms * 3.5f).coerceIn(0f, 1f)

    features.energy = (features.energy * 0.3f + rmsBoosted * 0.7f).coerceIn(0f, 1f)
    features.active = true
    features.markCaptureReceived()
  }

  /**
   * Processes live FFT byte arrays.
   */
  fun processFftData(fft: ByteArray) {
    if (fft.size < 8) return
    val captureSize = fft.size
    val halfSize = captureSize / 2

    var bassSum = 0f
    var midSum = 0f
    var trebleSum = 0f
    var bassCount = 0
    var midCount = 0
    var trebleCount = 0

    var k = 1
    while (k < halfSize) {
      val realIndex = k * 2
      val imagIndex = realIndex + 1
      if (imagIndex >= captureSize) break

      val real = fft[realIndex].toInt().toFloat()
      val imaginary = fft[imagIndex].toInt().toFloat()
      val mag = hypot(real, imaginary) / 128f

      when {
        k < 8 -> { bassSum += mag; bassCount++ }
        k < 64 -> { midSum += mag; midCount++ }
        else -> { trebleSum += mag; trebleCount++ }
      }
      k++
    }

    val bass = if (bassCount > 0) (bassSum / bassCount * 2.2f).coerceIn(0f, 1f) else 0f
    val mid = if (midCount > 0) (midSum / midCount * 2.5f).coerceIn(0f, 1f) else 0f
    val treble = if (trebleCount > 0) (trebleSum / trebleCount * 2.8f).coerceIn(0f, 1f) else 0f
    val energy = (bass * 0.5f + mid * 0.35f + treble * 0.15f).coerceIn(0f, 1f)

    // Smooth natural audio feature response
    features.bass = features.bass * 0.3f + bass * 0.7f
    features.mid = features.mid * 0.3f + mid * 0.7f
    features.treble = features.treble * 0.3f + treble * 0.7f
    features.energy = features.energy * 0.3f + energy * 0.7f
    features.beat = if (bass > 0.35f) 1f else 0f
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

  fun start(onWaveform: (ByteArray) -> Unit, onFFT: (ByteArray) -> Unit) {
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
