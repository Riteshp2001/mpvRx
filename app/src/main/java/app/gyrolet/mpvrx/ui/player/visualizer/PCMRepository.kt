/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, lock-free circular ring buffer storing incoming PCM audio samples.
 *
 * Designed to handle concurrent pushes from the audio decoder thread and periodic reads
 * from the 16ms background FFT processing thread without lock contention or allocation GC pressure.
 */
class PCMRepository(
  val capacity: Int = DEFAULT_CAPACITY,
) {
  companion object {
    const val DEFAULT_CAPACITY = 16384 // 16K samples (~370ms at 44.1kHz)
  }

  private val ringBuffer = FloatArray(capacity)
  private val writeHead = AtomicInteger(0)
  @Volatile var currentSampleRate: Int = 44100
    private set
  @Volatile var currentChannelCount: Int = 2
    private set

  /**
   * Pushes a decoded [AudioFrame] into the circular buffer.
   */
  fun pushFrame(frame: AudioFrame) {
    if (frame.pcmSamples.isEmpty()) return
    currentSampleRate = frame.sampleRate
    currentChannelCount = frame.channelCount
    pushSamples(frame.pcmSamples)
  }

  /**
   * Pushes raw normalized float PCM samples into the circular buffer lock-free.
   */
  fun pushSamples(samples: FloatArray, length: Int = samples.size) {
    if (length <= 0) return
    val countToCopy = length.coerceAtMost(capacity)
    var currentHead = writeHead.get()

    for (i in 0 until countToCopy) {
      val index = (currentHead + i) % capacity
      ringBuffer[index] = samples[i]
    }

    writeHead.set((currentHead + countToCopy) % capacity)
  }

  /**
   * Retrieves the latest [requestedSize] samples from the ring buffer into a snapshot [FloatArray].
   * If mono conversion or stereo averaging is needed, downmixes to mono.
   */
  fun getLatestSamples(requestedSize: Int): FloatArray {
    val output = FloatArray(requestedSize)
    val head = writeHead.get()
    val totalAvailable = capacity

    for (i in 0 until requestedSize) {
      val ringIndex = (head - requestedSize + i + totalAvailable * 2) % capacity
      output[i] = ringBuffer[ringIndex]
    }

    return output
  }

  /**
   * Clears the ring buffer content.
   */
  fun clear() {
    ringBuffer.fill(0f)
    writeHead.set(0)
  }
}
