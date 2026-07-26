package app.gyrolet.mpvrx.utils.media

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

class AudioEqualizerManager {
  private var equalizer: Equalizer? = null
  private var loudnessEnhancer: LoudnessEnhancer? = null
  private var isInitialized = false

  fun initSession(sessionId: Int = 0) {
    release()
    try {
      val eq = Equalizer(0, sessionId)
      equalizer = eq
      isInitialized = true
      Log.d("AudioEqualizerManager", "Hardware Equalizer attached to audio session $sessionId")
    } catch (e: Exception) {
      Log.e("AudioEqualizerManager", "Failed to attach hardware Equalizer: ${e.message}")
    }

    try {
      val enhancer = LoudnessEnhancer(sessionId)
      loudnessEnhancer = enhancer
      Log.d("AudioEqualizerManager", "Hardware LoudnessEnhancer attached to audio session $sessionId")
    } catch (e: Exception) {
      Log.e("AudioEqualizerManager", "Failed to attach LoudnessEnhancer: ${e.message}")
    }
  }

  fun updateState(enabled: Boolean, bandGains: List<Int>, volumeBoostDb: Int) {
    if (!isInitialized) {
      initSession(0)
    }

    try {
      equalizer?.let { eq ->
        eq.enabled = enabled
        if (enabled) {
          val numBands = eq.numberOfBands.toInt()
          bandGains.forEachIndexed { index, db ->
            if (index < numBands) {
              val minMB = eq.bandLevelRange[0]
              val maxMB = eq.bandLevelRange[1]
              val targetMB = (db * 100).coerceIn(minMB.toInt(), maxMB.toInt()).toShort()
              eq.setBandLevel(index.toShort(), targetMB)
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e("AudioEqualizerManager", "Failed to set equalizer levels: ${e.message}")
    }

    try {
      loudnessEnhancer?.let { enhancer ->
        if (enabled && volumeBoostDb > 0) {
          enhancer.setTargetGain(volumeBoostDb * 100)
          enhancer.enabled = true
        } else {
          enhancer.enabled = false
        }
      }
    } catch (e: Exception) {
      Log.e("AudioEqualizerManager", "Failed to set LoudnessEnhancer gain: ${e.message}")
    }
  }

  fun release() {
    runCatching { equalizer?.release() }
    runCatching { loudnessEnhancer?.release() }
    equalizer = null
    loudnessEnhancer = null
    isInitialized = false
  }
}
