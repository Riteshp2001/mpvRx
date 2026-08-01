/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * Supplies a per-process audio session id shared between mpv (configured as
 * `ao=audiotrack --audiotrack-session-id=<id>`) and the visualizer overlays.
 *
 * Attaching [android.media.audiofx.Visualizer] to the app's own audio session requires no
 * RECORD_AUDIO permission (that permission is only needed to capture the output mix or another
 * app's session), which is how the visualizers work without a microphone permission.
 *
 * Automatically regenerates the session id when audio routing changes (headphone
 * connect/disconnect) so the Visualizer stays attached to the active output.
 */
object AudioSessionProvider {
  private var cachedId: Int = 0
  private var receiverRegistered = false

  private val routingReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          AudioManager.ACTION_AUDIO_BECOMING_NOISY,
          AudioManager.ACTION_HEADSET_PLUG,
          -> {
            cachedId = 0
          }
        }
      }
    }

  fun get(context: Context): Int {
    ensureReceiverRegistered(context)
    if (cachedId == 0) {
      val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      cachedId = manager.generateAudioSessionId()
    }
    return cachedId
  }

  private fun ensureReceiverRegistered(context: Context) {
    if (receiverRegistered) return
    receiverRegistered = true
    val filter =
      IntentFilter().apply {
        addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        addAction(AudioManager.ACTION_HEADSET_PLUG)
      }
    context.applicationContext.registerReceiver(routingReceiver, filter)
  }
}
