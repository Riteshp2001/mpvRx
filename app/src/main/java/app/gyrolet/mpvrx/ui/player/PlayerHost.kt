/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat

data class PlayerLookupHints(
  val canonicalTitle: String? = null,
  val imdbId: String? = null,
  val tmdbId: Int? = null,
  val mediaType: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
)

/**
 * Abstraction over host requirements so the player logic can run in an Activity or a Screen.
 */
interface PlayerHost {
  val context: Context
  val windowInsetsController: WindowInsetsControllerCompat
  val audioManager: AudioManager

  // Host OS primitives with non-conflicting names
  val hostWindow: Window
  val hostWindowManager: WindowManager
  val hostContentResolver: ContentResolver
  var hostRequestedOrientation: Int

  fun requestAudioFocus(): Boolean

  fun abandonAudioFocus()

  fun currentMediaLookupHint(): String? = null

  fun currentPlayerLookupHints(): PlayerLookupHints = PlayerLookupHints()

  fun currentThumbnailSource(): String? = null

  fun isCurrentMediaKnownAudio(): Boolean = false
}
