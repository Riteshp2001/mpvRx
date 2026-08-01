/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

/**
 * Represents a decoded PCM audio buffer frame exposed by the audio pipeline.
 *
 * @property sampleRate The sampling rate in Hz (e.g. 44100 or 48000).
 * @property channelCount The number of audio channels (e.g. 1 for mono, 2 for stereo).
 * @property pcmSamples Normalized PCM float samples in range [-1.0f, 1.0f].
 */
data class AudioFrame(
  val sampleRate: Int = 44100,
  val channelCount: Int = 2,
  val pcmSamples: FloatArray = FloatArray(0),
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as AudioFrame

    if (sampleRate != other.sampleRate) return false
    if (channelCount != other.channelCount) return false
    if (!pcmSamples.contentEquals(other.pcmSamples)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = sampleRate
    result = 31 * result + channelCount
    result = 31 * result + pcmSamples.contentHashCode()
    return result
  }
}
