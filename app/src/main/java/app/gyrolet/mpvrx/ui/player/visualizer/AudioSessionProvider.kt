/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.Context
import android.media.AudioManager

/**
 * Supplies a stable per-process audio session id that is shared between mpv (configured as
 * `ao=audiotrack --audiotrack-session-id=<id>`) and the visualizer overlays.
 *
 * Attaching [android.media.audiofx.Visualizer] to the app's own audio session requires no
 * RECORD_AUDIO permission (that permission is only needed to capture the output mix or another
 * app's session), which is how the visualizers work without a microphone permission.
 */
object AudioSessionProvider {
  private var cachedId: Int = 0

  fun get(context: Context): Int {
    if (cachedId == 0) {
      val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      cachedId = manager.generateAudioSessionId()
    }
    return cachedId
  }
}
