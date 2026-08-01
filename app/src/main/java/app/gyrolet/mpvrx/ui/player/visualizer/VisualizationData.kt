/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

/**
 * Immutable visualization state snapshot emitted by [FFTProcessor] / [VisualizerViewModel]
 * to drive Compose visualizer views.
 *
 * @property magnitudes Normalized spectrum bar heights [0.0f, 1.0f] (e.g. 64 bars).
 * @property waveform Normalized time-domain PCM samples for oscilloscope wave rendering.
 * @property bass Bass frequency band energy level [0.0f, 1.0f].
 * @property mid Mid frequency band energy level [0.0f, 1.0f].
 * @property treble Treble frequency band energy level [0.0f, 1.0f].
 * @property energy Overall audio energy level [0.0f, 1.0f].
 * @property beat Dynamic beat envelope level [0.0f, 1.0f].
 * @property isPlaying Indicates whether active audio playback is producing visualization data.
 */
data class VisualizationData(
  val magnitudes: FloatArray = FloatArray(BAR_COUNT),
  val waveform: FloatArray = FloatArray(WAVE_COUNT),
  val bass: Float = 0f,
  val mid: Float = 0f,
  val treble: Float = 0f,
  val energy: Float = 0f,
  val beat: Float = 0f,
  val isPlaying: Boolean = false,
) {
  companion object {
    const val BAR_COUNT = 64
    const val WAVE_COUNT = 128
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as VisualizationData

    if (!magnitudes.contentEquals(other.magnitudes)) return false
    if (!waveform.contentEquals(other.waveform)) return false
    if (bass != other.bass) return false
    if (mid != other.mid) return false
    if (treble != other.treble) return false
    if (energy != other.energy) return false
    if (beat != other.beat) return false
    if (isPlaying != other.isPlaying) return false

    return true
  }

  override fun hashCode(): Int {
    var result = magnitudes.contentHashCode()
    result = 31 * result + waveform.contentHashCode()
    result = 31 * result + bass.hashCode()
    result = 31 * result + mid.hashCode()
    result = 31 * result + treble.hashCode()
    result = 31 * result + energy.hashCode()
    result = 31 * result + beat.hashCode()
    result = 31 * result + isPlaying.hashCode()
    return result
  }
}
