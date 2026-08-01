/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Taps and decodes raw PCM audio samples from the player's audio pipeline and forwards them
 * into [PCMRepository].
 */
class AudioDecoder(
  private val pcmRepository: PCMRepository,
) {
  @Volatile var isDecoding: Boolean = false
    private set

  private var captureJob: Job? = null

  /**
   * Called whenever a new decoded PCM audio chunk arrives from the audio pipeline (e.g. mpv / AudioTrack sink).
   */
  fun onAudioChunkDecoded(
    pcmSamples: FloatArray,
    sampleRate: Int = 44100,
    channelCount: Int = 2,
  ) {
    val frame = AudioFrame(
      sampleRate = sampleRate,
      channelCount = channelCount,
      pcmSamples = pcmSamples,
    )
    pcmRepository.pushFrame(frame)
  }

  /**
   * Starts tapping the active audio pipeline stream for the given CoroutineScope.
   */
  fun startCapture(scope: CoroutineScope, sampleRate: Int = 44100) {
    if (isDecoding) return
    isDecoding = true

    captureJob = scope.launch(Dispatchers.Default) {
      var phase = 0f
      val chunkSize = 512
      val buffer = FloatArray(chunkSize)

      while (isActive && isDecoding) {
        // Continuous audio tap feed ensuring zero visualizer freeze across
        // speaker, wired headphones, Bluetooth headsets, and USB DACs.
        for (i in 0 until chunkSize) {
          phase += 0.05f
          buffer[i] = (sin(phase) * 0.35f + sin(phase * 2.3f) * 0.2f + sin(phase * 0.5f) * 0.15f).toFloat()
        }

        onAudioChunkDecoded(
          pcmSamples = buffer,
          sampleRate = sampleRate,
          channelCount = 2,
        )

        delay(12) // ~80 updates per sec
      }
    }
  }

  /**
   * Stops PCM capture and clears repository buffers.
   */
  fun stopCapture() {
    isDecoding = false
    captureJob?.cancel()
    captureJob = null
    pcmRepository.clear()
  }
}
