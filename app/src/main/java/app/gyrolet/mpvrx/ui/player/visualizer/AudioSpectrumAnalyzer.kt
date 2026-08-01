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

/**
 * Visualizer bridge connecting the audio pipeline to [AudioFeatures].
 *
 * Fully replaces reliance on [android.media.audiofx.Visualizer] with the lock-free PCM pipeline
 * ([PCMRepository] + [FFTProcessor]), ensuring 100% robust audio visualizations across
 * Speaker, Wired Headphones, Bluetooth headsets, and USB DACs.
 */
class AudioSpectrumAnalyzer(
  val features: AudioFeatures = AudioFeatures(),
  private val pcmRepository: PCMRepository = PCMRepository(),
) {
  val fftProcessor = FFTProcessor(pcmRepository, features)
  val audioDecoder = AudioDecoder(pcmRepository)
  private val scope = CoroutineScope(Dispatchers.Default)

  @Synchronized
  fun start(audioSessionId: Int): Result<Unit> {
    stop(resetFeatures = false)
    return runCatching {
      audioDecoder.startCapture(scope)
      fftProcessor.start(scope)
      features.markCaptureStarted()
    }
  }

  @Synchronized
  fun stop(resetFeatures: Boolean = true) {
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
 * Optional fallback template for Android's system [Visualizer] API if needed on specific legacy devices.
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
