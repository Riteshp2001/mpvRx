/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel managing the lifecycle and state flow of the PCM music visualizer pipeline.
 *
 * Connects [AudioDecoder] -> [PCMRepository] -> [FFTProcessor] and exposes immutable
 * [VisualizationData] StateFlow to Jetpack Compose UI views.
 */
class VisualizerViewModel(
  val pcmRepository: PCMRepository = PCMRepository(),
  val audioDecoder: AudioDecoder = AudioDecoder(pcmRepository),
  val fftProcessor: FFTProcessor = FFTProcessor(pcmRepository),
) : ViewModel() {

  /** StateFlow emitting visualization data for UI views. */
  val visualizationData: StateFlow<VisualizationData> = fftProcessor.visualizationData

  /** AudioFeatures volatile instance consumed by OpenGL renderers (Particle, Blob, Cuboid, Galaxy). */
  val audioFeatures: AudioFeatures = fftProcessor.audioFeatures

  /**
   * Starts visualizer capture and background FFT processing.
   */
  fun startVisualizer(sampleRate: Int = 44100) {
    audioDecoder.startCapture(viewModelScope, sampleRate = sampleRate)
    fftProcessor.start(viewModelScope)
  }

  /**
   * Stops visualizer capture and clears background processing.
   */
  fun stopVisualizer() {
    audioDecoder.stopCapture()
    fftProcessor.stop()
  }

  override fun onCleared() {
    super.onCleared()
    stopVisualizer()
  }
}
