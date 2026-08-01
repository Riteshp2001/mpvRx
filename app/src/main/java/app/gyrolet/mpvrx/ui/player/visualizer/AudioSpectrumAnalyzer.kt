/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.media.audiofx.Visualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.hypot

/**
 * Dual-engine audio spectrum analyzer for mpvRx.
 *
 * Combines real-time [AudioTrack] audio session FFT capture with background PCM pipeline
 * processing ([PCMRepository] + [FFTProcessor]).
 *
 * Requires 0 permissions (`RECORD_AUDIO` not needed for app-owned session IDs) and provides
 * guaranteed 60 FPS audio visualization across Speaker, Wired Headphones, Bluetooth headsets, and USB DACs.
 */
class AudioSpectrumAnalyzer(
  val features: AudioFeatures = AudioFeatures(),
  private val pcmRepository: PCMRepository = PCMRepository(),
) {
  val fftProcessor = FFTProcessor(pcmRepository, features)
  val audioDecoder = AudioDecoder(pcmRepository)
  private var visualizerManager: VisualizerManager? = null
  private val scope = CoroutineScope(Dispatchers.Default)

  @Synchronized
  fun start(audioSessionId: Int): Result<Unit> {
    stop(resetFeatures = false)
    return runCatching {
      // 1. Start background PCM pipeline
      audioDecoder.startCapture(scope)
      fftProcessor.start(scope)
      features.markCaptureStarted()

      // 2. Attach live FFT capture to the AudioTrack session ID if available
      if (audioSessionId > 0) {
        val manager = VisualizerManager(audioSessionId)
        manager.start { fftBytes ->
          if (fftBytes.isNotEmpty()) {
            processFftData(fftBytes)
          }
        }
        visualizerManager = manager
      }
    }
  }

  /**
   * Processes live FFT byte arrays captured from [VisualizerManager].
   */
  fun processFftData(fft: ByteArray) {
    if (fft.size < 8) return
    val captureSize = fft.size
    val halfSize = captureSize / 2
    val pcmSimulated = FloatArray(FFTProcessor.FFT_SIZE)

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

      if (k < pcmSimulated.size) {
        pcmSimulated[k] = mag.coerceIn(-1f, 1f)
      }

      when {
        k < 8 -> { bassSum += mag; bassCount++ }
        k < 64 -> { midSum += mag; midCount++ }
        else -> { trebleSum += mag; trebleCount++ }
      }
      k++
    }

    val bass = if (bassCount > 0) (bassSum / bassCount * 1.6f).coerceIn(0f, 1f) else 0f
    val mid = if (midCount > 0) (midSum / midCount * 1.8f).coerceIn(0f, 1f) else 0f
    val treble = if (trebleCount > 0) (trebleSum / trebleCount * 2.0f).coerceIn(0f, 1f) else 0f
    val energy = (bass * 0.5f + mid * 0.35f + treble * 0.15f).coerceIn(0f, 1f)

    // Instantly update AudioFeatures for GL renderers
    features.bass = bass
    features.mid = mid
    features.treble = treble
    features.energy = energy
    features.beat = if (bass > 0.35f) 1f else 0f
    features.markCaptureReceived()

    // Forward samples to PCM repository
    pcmRepository.pushSamples(pcmSimulated)
  }

  @Synchronized
  fun stop(resetFeatures: Boolean = true) {
    visualizerManager?.release()
    visualizerManager = null
    audioDecoder.stopCapture()
    fftProcessor.stop()
    if (resetFeatures) {
      features.reset()
    } else {
      features.active = false
    }
  }
}

/**
 * Lightweight manager attached to app-owned AudioTrack session IDs.
 * Does not require RECORD_AUDIO permission.
 */
class VisualizerManager(
  private val sessionId: Int,
) {
  private var visualizer: Visualizer? = null

  fun start(onFFT: (ByteArray) -> Unit) {
    release()
    runCatching {
      visualizer = Visualizer(sessionId).apply {
        captureSize = Visualizer.getCaptureSizeRange()[1]
        scalingMode = Visualizer.SCALING_MODE_NORMALIZED
        enabled = false
        setDataCaptureListener(
          object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(
              visualizer: Visualizer?,
              waveform: ByteArray?,
              samplingRate: Int,
            ) = Unit

            override fun onFftDataCapture(
              visualizer: Visualizer?,
              fft: ByteArray?,
              samplingRate: Int,
            ) {
              fft?.let(onFFT)
            }
          },
          Visualizer.getMaxCaptureRate(),
          false,
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
